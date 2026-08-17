# Clustering — what is shared and what is node-local

**Goal of v3 lot B**: two or more gateway replicas behind a load balancer,
sharing one PostgreSQL, with no request affinity and no silently divergent
behaviour between nodes.

**Constraint**: no Redis. Everything goes through the Postgres the product
already requires. "One jar, one Postgres" is the argument; adding a second infra
container to solve locking would spend it.

Lot B is **in progress**. Until it closes, running multiple replicas is still not
supported end to end — see [Where lot B stands](#where-lot-b-stands) for exactly
what is missing, and [`../functional/limitations.md`](../functional/limitations.md).

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
| `AdminSeedRunner` | idempotent on `api_key_hash` | needs a concurrent-cold-start test (**B.4**) |
| `InMemoryDeferredJobStore` | heap | **must be persisted** (**B.2**) — jobs are lost on restart and invisible to other nodes |
| `RateLimiter` buckets | `ConcurrentHashMap` | **must be shared** (**B.3**) — the limit is per process, so N replicas allow N × the quota |
| `CarbonAwareDispatchWorker` | runs on every node | **must be gated** (**B.4**) — dispatching twice is not harmless |
| `DecisionPurgeWorker` | runs on every node | **must be gated** (**B.4**) — purging twice is harmless but the pattern needs a rule |
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

## Where lot B stands

| Batch | Subject | Status |
|---|---|---|
| B.0 | This inventory | done |
| B.1 | Persist and propagate the routing config | **done** |
| B.2 | Persist deferred jobs | to do |
| B.3 | Distributed rate limiting, without Redis | to do |
| B.4 | Leader-gated scheduled work | to do |
| B.5 | Prove it end to end, then say it | to do |

Until B.2–B.4 land, a second replica would double the effective rate limit and
would not see another node's queued jobs. Routing is the part that is safe to
replicate today.
