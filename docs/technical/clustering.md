# Clustering — what is shared and what is node-local

**Goal of v3 lot B**: two or more gateway replicas behind a load balancer,
sharing one PostgreSQL, with no request affinity and no silently divergent
behaviour between nodes.

**Constraint**: no Redis. Everything goes through the Postgres the product
already requires. "One jar, one Postgres" is the argument; adding a second infra
container to solve locking would spend it.

Lot B is **nearly closed**: every row of the inventory below now reads "fine",
with one configuration caveat (the rate-limit store) and one batch outstanding
(B.5, the end-to-end proof). See [Where lot B stands](#where-lot-b-stands) and
[`../functional/limitations.md`](../functional/limitations.md).

## The inventory

Every piece of state that lives in a node's heap, and its verdict. This table is
the acceptance criterion for the whole lot: at the end, every row reads "fine"
and says why.

| State | Where it lives today | Verdict |
|---|---|---|
| Live `RoutingConfig` + cascade band | `routing_config` row, cached per node | **fine** — shared, polled (B.1) ✅ |
| Semantic route indexes | rebuilt in memory per node | **fine** — derived from the shared config; rebuilt when it changes |
| `RequestEmbeddingMemo` | scoped value, per request | **fine** by construction — it never outlives one request |
| `InMemoryAttributionCache` (LRU 500) | heap | **fine** — a cache keyed on prompt hash + embedding model + config version; a miss costs a recomputation, never a wrong answer |
| Conformal snapshot (60 s TTL) | heap, per node | **fine** — a read-through cache of `conformal_calibration`; nodes converge within 60 s of a recalibration |
| `AdminSeedRunner` | idempotent on `api_key_hash`, under a leader lock | **fine** — one seeder, and losing the key race no longer fails a boot (B.4) ✅ |
| Deferred job queue | `deferred_job` table, claimed with `SKIP LOCKED` | **fine** — shared, durable, exactly-once per claim (B.2) ✅ |
| `RateLimiter` buckets | heap, or the `rate_limit_bucket` table | **fine** — shared when `gatewai.ratelimit.store=postgres` (B.3) ✅; the heap default is per process, so a cluster must set it |
| `CarbonAwareDispatchWorker` | runs on every node | **fine** — every node *should* work the queue; the claim is what makes that safe (B.2). No leader gating needed, and B.4 says so rather than adding it |
| `DecisionPurgeWorker` | runs on every node, gated on an advisory lock | **fine** — one purge per interval across the cluster (B.4) ✅ |
| Routing-config poll | runs on every node | **fine** — a read, and it must run everywhere (B.1) |

## Routing configuration (B.1)

### What was wrong

The rules lived only in `ClassifierProperties`, a singleton mutated in place. A
`PUT /v1/admin/routing` on node A therefore left node B routing on the old rules
**indefinitely** — not until the next restart, forever — while each node stamped
its own `routing_config_version` onto its own decisions. The failure mode was not
an error anywhere: two nodes would explain the same prompt under different rules,
each explanation internally consistent, one of them wrong. Nothing in the trace
could tell you which.

A restart made it worse rather than better: it reset the node to
`application.properties`, discarding every edit an operator had made through the
API.

### How it works now

```
PUT /v1/admin/routing  →  RoutingConfigService (validate)
                       →  PersistentRoutingConfigPort   (@Primary RoutingConfigPort)
                          ├─ store.saveConfig(...)      → routing_config, revision++
                          └─ apply locally              → ClassifierProperties
                                                          (what the classifier reads)

every 5 s, on every node:  store.load() → revision changed? → apply locally
```

- **`PersistentRoutingConfigPort`** (`infrastructure/llm`) is the node's view: a
  cache over the shared store. `ClassifierRoutingConfigAdapter` stays what it
  was, the node-local copy, so a classification is still a memory read and never
  a query.
- **`RoutingConfigStore`** (out port, `JpaRoutingConfigStore`) owns the row.
- **Writes persist first, apply second.** A write that cannot be stored fails the
  request instead of taking effect on one node only — which is the divergence
  this batch exists to remove.
- **Reads at startup, before the server accepts connections** (`@PostConstruct`,
  not an `ApplicationRunner`: runners fire after the port is open, which would
  leave a window where requests route on defaults the cluster has already
  replaced).

### Propagation is polling, and that is deliberate

`LISTEN`/`NOTIFY` was the alternative. Polling was chosen because the divergence
window is then a **bounded, documented number** —
`gatewai.routing.config-sync-interval-ms`, 5 s by default — rather than a
connection that can stop delivering without anyone noticing. One single-row
`SELECT` per node per interval is not a cost worth optimising against that.
`NOTIFY` remains open as a latency optimisation on top, not as the mechanism.

`revision` is the signal, not the identity. It is bumped on every write, even one
storing identical values, so a node decides whether to re-read with a `long`
comparison. Identity stays `routing_config_version`, which is content-addressed.
Intermediate revisions are **coalesced**: a node that polls once after two writes
adopts the latest state and never sees the one in between.

### Consequences to know

- **`gatewai_routing_config_changes_total` increments once per node per change.**
  With N replicas, one edit shows up as N on a summed panel. The counter also
  only moves on a node that has *observed* the previous version — a node that
  served no traffic between two edits reports nothing, because it never saw a
  transition. Read the drift panel per instance, or divide by the replica count.
- **A config edit invalidates the conformal routing calibration on every node at
  once**, which is the point: `routing_config_version` is now identical
  cluster-wide, so `gatewai_conformal_calibration_stale` goes to 1 everywhere
  instead of on whichever node happened to take the edit.
- **The cascade margin band is written separately** from the rules, on its own
  statement, and stays out of `routing_config_version` (v2 batch 4, D26). One
  `PUT` therefore produces **two** revisions.
- **A database outage keeps the last-known rules.** The poll logs a warning and
  swallows the failure; it never resets a node to its defaults. (The gateway is
  degraded anyway during such an outage — auth and the cache need the same
  database — so this buys the *rules* surviving, not the gateway surviving.)
- **The row is created by the first node that starts**, from its own
  `application.properties`, with `ON CONFLICT (id) DO NOTHING`. Whoever loses a
  concurrent first start adopts the winner's configuration and logs that it did.
  Two nodes with divergent property files therefore converge instead of splitting
  — but which one wins is a race, so keep the files identical.

### Verified, on two JVMs sharing one Postgres

Both nodes ran the packaged jar, `mock` profile, one pgvector container:

| Criterion | Result |
|---|---|
| Edit on node A reaches node B | **2.05 s**, one poll (5 s interval) — rules *and* band |
| Revisions | A applied 2 then 3 (its own write); B adopted 3 directly, skipping 2 |
| Restart loads the persisted config | node B restarted at **revision 3**, not the `application.properties` defaults |
| `routing_config_version` across nodes | identical (`ce59c5bc8f5961ff`) on decisions recorded by both |
| Concurrent first start | the losing `INSERT … ON CONFLICT DO NOTHING` reports `INSERT 0 0`; the winner's row is untouched |
| Second live config impossible | `routing_config_single_row` rejects `id = 2` |
| Change counter | `gatewai_routing_config_changes_total` read **1.0 on each** of the two nodes after one edit |
| Database outage | one failed poll → `WARN … keeping revision 5`; the node kept routing, recovered, and accepted the next `PUT` at revision 6 |

## Deferred jobs (B.2)

### What was wrong

The queue was a `ConcurrentHashMap`. Queued jobs were lost on restart, and a job
submitted on node A was invisible to node B — including to node B's dispatch
worker, which was supposed to run it. A client polling
`GET /v1/chat/completions/async/{id}` through a load balancer got a `404` for a
job that existed, on whichever node had not taken the submission.

### How it works now

The queue is the `deferred_job` table, and a worker **claims** instead of listing:
`SELECT … WHERE status = 'QUEUED' ORDER BY submitted_at FOR UPDATE SKIP LOCKED
LIMIT 1`, inside the transaction that flips the row to `RUNNING`. Reading the
queue and then writing each status is two operations and a race that both workers
win; the claim is one operation, so a second worker sees an empty queue rather
than the same job. The mechanics — one job per claim, the per-tick cap, the lease
and the at-least-once caveat — are in
[`carbon-aware-dispatch.md`](carbon-aware-dispatch.md).

### This is why the dispatch worker is *not* leader-gated

B.4 asks for scheduled work to be gated on a leader lock and leaves the dispatch
worker's answer open. The answer is **no gate**: a leader lock would make one node
do all the dispatching and turn the other into a spectator, which is the opposite
of what a shared queue is for. Every node runs the worker, and the claim decides
who gets what. Gating belongs to jobs that are *not* idempotent and have no claim
of their own — the decision purge.

### Consequences to know

- **A restart mid-job costs one lease**, not the job. The row stays `RUNNING`
  until `lease_expires_at` passes, then any node requeues it.
- **`claimed_by` is the answer to "where did this run?"** — `gatewai.instance-id`
  if set, `host:pid` otherwise. It survives the completion write, which is why
  that write is column-scoped.
- **Prompts are now persisted here.** The queue holds the request in clear text
  and has no retention policy yet; the vector cache was the only other place
  prompt text lands.

### Verified, on two JVMs sharing one Postgres

| Criterion | Result |
|---|---|
| Jobs survive a restart | 2 jobs submitted on node A stayed `QUEUED` with **both nodes stopped** |
| Submitted on A, completed on B | node B (started after A was killed) ran both, `claimed_by = node-b` |
| Readable from either node | the restarted node A returned the full result for a job node B ran |
| Exactly once under concurrency | 30 more jobs with both nodes dispatching: **32 jobs, 32 executions, 0 duplicates**, counted in `request_log` by `correlation_id` |
| Work actually spread | 10 / 22 split between nodes |
| Lease recovery | integration test: an unfinished claim returns to `QUEUED`, zone cleared, claimable again |

## Rate limiting (B.3)

### What was wrong

Bucket4j's buckets were a `ConcurrentHashMap`, so "60 requests per minute" meant
60 *per process*. Two replicas behind a load balancer let a client through 120
times a minute and neither node was doing anything wrong. Measured on two nodes
with the limit set to 6/min: **10 of 10 requests allowed**.

### How it works now

`gatewai.ratelimit.store=postgres` moves the buckets into the
`rate_limit_bucket` table, read with `SELECT … FOR UPDATE` inside the transaction
that writes the new state back. Same two nodes, same 6/min limit: **6 allowed, 4
refused** with `Retry-After: 9`.

`RateLimiter` is now an interface with two implementations, and the limit plus the
`Retry-After` calculation live on it as shared statics — the two stores enforce one
definition rather than two that happen to agree today. Details, including why not
`SKIP LOCKED` and why not an advisory lock, are in
[`security.md`](security.md#rate-limiting).

### The default is still `memory`, on purpose

The shared store costs **3.4 ms p50 / 3.8 ms p95** per limited request against
21–24 µs in the heap (`gatewai_ratelimit_check_seconds`, quantiles published by
default). Under 1 % of a real model call, ~15 % of a cache hit. A single node is
the common deployment and is *correct* with the heap store, so it keeps the cheap
path and a cluster sets one property. The consequence — a cluster that forgets to
set it silently grants N × the quota — is the one footgun lot B leaves standing,
which is why it is stated here, in `security.md`, in `limitations.md` and in the
property's own comment.

Because the cost was measured rather than feared, the local-token-batching
optimisation the roadmap held in reserve was **not** built: at 3.8 ms there is
nothing to buy.

## Scheduled work (B.4)

### The rule

A periodic job needs gating only when running it twice is wrong **and** it has no
claim of its own. That is a narrower set than "everything scheduled", and the
inventory above says which side each job is on:

| Job | Gated? | Why |
|---|---|---|
| Decision purge | **yes** | the same `DELETE` on every node, and N log lines describing one event |
| Admin seeding | **yes** | two nodes with no configured key would create two admins with two keys |
| Routing-config poll | no | it is a read, and it **must** run everywhere (B.1) |
| Carbon-aware dispatch | no | the queue's own `SKIP LOCKED` claim coordinates it; a gate would elect one dispatcher and idle the rest (B.2) |
| Conformal snapshot refresh | no | a per-node read-through cache |

### There is no leader

`LeaderLock` is named after what it does, not after an algorithm: no election, no
term, no heartbeat, nothing to fail over. Each tick asks
`pg_try_advisory_xact_lock(namespace, task)`, does the work if it gets it, and
forgets. A node that dies holds nothing, so the next tick on any node wins — that
is the entire recovery story, and it needs no operator.

Three choices worth stating:

- **Transaction-scoped, not session-scoped.** A session lock must be released by
  hand, so a node killed mid-job holds it until its connection is reaped: "one
  node died" would become "nothing runs any more". A transaction lock is released
  by the commit, the rollback, *or* the connection dying.
- **The work runs inside the lock's transaction**, so the lock is held for exactly
  as long as the job and the job's writes share its connection. A job writing
  through another `DataSource` would fall outside that protection; none does, and
  `LeaderTask` is where a new one has to be declared.
- **One declared lock id per task**, not a hash of its name. Two jobs sharing a key
  would silently serialize against each other, and ids are namespaced by
  `"gatewai".hashCode()` so another application on the same database cannot
  collide. Ids are permanent: reusing one would let a new node's job block an old
  node's during a rolling upgrade.

**No migration.** B.4 is the one batch of this lot that adds no schema — PostgreSQL
already has the primitive.

### Verified, on two JVMs sharing one Postgres

The natural experiment (watch two nodes purge) proves nothing: the second node's
purge would find nothing to delete either way. So the gate was tested by **holding
the lock from a `psql` session** and watching what the nodes did.

| Criterion | Result |
|---|---|
| The purge gate is real | with the lock held externally, both nodes logged `skipping` on **every** tick (7 and 6 skips over 20 s at a 3 s interval) and the purgeable rows stayed in place |
| The key computation matches | that is only possible because Java's `pg_try_advisory_xact_lock(-189118924, 1)` and the SQL session asked for the same lock |
| Release resumes work | the tick after the lock was freed purged the rows, on whichever node ticked first |
| Losing a node needs no intervention | node B killed → node A purged on its next tick |
| The seed gate is real | a node booting while `ADMIN_SEED` was held logged *"Another instance is seeding the admin client; skipping"*, left `api_client` **empty**, and **started anyway** |
| Dying releases the lock | terminating the holder's backend dropped it (`pg_locks` empty), and the next start seeded exactly one admin |
| Two nodes, one key | started together with the same `GATEWAI_ADMIN_API_KEY` against an empty table: **exactly one** admin, both nodes up, both accepting the key |

## Where lot B stands

| Batch | Subject | Status |
|---|---|---|
| B.0 | This inventory | done |
| B.1 | Persist and propagate the routing config | **done** |
| B.2 | Persist deferred jobs | **done** |
| B.3 | Distributed rate limiting, without Redis | **done** (opt-in) |
| B.4 | Leader-gated scheduled work | **done** |
| B.5 | Prove it end to end, then say it | to do |

One thing still needs an operator's attention: **the rate limit stays per process
until you set `gatewai.ratelimit.store=postgres`** — the mechanism exists, the safe
value is not the default (B.3 explains why). Everything else in the inventory now
reads "fine". What B.5 owes is not another mechanism but the **proof as a whole**:
a two-replica compose file, one scenario exercising all four batches together, and
the rewrite of `limitations.md` that this page has been getting ahead of.
