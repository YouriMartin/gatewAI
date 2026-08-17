# Data model

All persistence lives in **one PostgreSQL** database: the relational metrics/admin
tables (JPA) and the vector cache (pgvector) share it. Sources:
`infrastructure/persistence/*` and the pgvector vector store.

## Why one database

Fewer containers, less RAM, one backup/restore story — consistent with the green
stance, and adequate for the workload. The vector store is accessed only through
Spring AI's `VectorStore` interface, so it can be moved to Qdrant later without
touching the cache logic.

## `request_log` (metrics)

Persisted once per served request by `ChatCompletionService`
(`RequestLogEntity` ⇄ domain `RequestLog` ⇄ `JpaRequestLogAdapter`).

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | PK |
| `correlation_id` | varchar(64) | ingress-assigned id shared by every record of the same request (v2 batch 0.3), indexed, nullable |
| `timestamp` | instant | when served |
| `model` | text | model that actually served it |
| `prompt_hash` | char(64) | **SHA-256 of the prompt** — the prompt text is not stored |
| `prompt_tokens`, `completion_tokens`, `total_tokens` | int | usage |
| `latency_ms` | bigint | wall-clock latency |
| `client_id` | text | owning client (from the request context), nullable |
| `cost_eur` | double | actual cost |
| `energy_kwh` | double | estimated energy |
| `grams_co2` | double | estimated emissions |
| `cost_avoided_eur` | double | saved vs premium baseline |
| `grams_co2_avoided` | double | saved vs premium baseline |
| `cache_hit` | boolean | served from cache |

The green columns flatten the `GreenMetrics` value object. Rows are effectively
immutable (`updatable = false`). Reporting reads them via
`findBetween(from, to)` and aggregates in memory (`ReportAggregator`).

`correlation_id` comes from `CorrelationIdFilter`, which honours an inbound
`X-Request-Id` (or generates a UUID) and always echoes it back on the response.
`ApiKeyAuthenticationFilter` binds it into `RequestContext`, so the whole advisor
chain shares it. It is null when there was no HTTP request — a direct use-case
call — and equals the job id on the deferred dispatch path.

> Privacy by design: only a **hash** of the prompt is stored, never the text. The
> prompt text does live in the vector cache (needed for similarity search), scoped
> per client.

## `routing_decision` and `cache_decision` (decision tracing)

Written off the request path by `AsyncDecisionRecorder` (v2 batch 2), one row per
decision. Both join back to `request_log` on `correlation_id`.

`routing_decision`: `prompt_hash` + `prompt_length` · `embedding_model` ·
`routing_config_version` (see below) · `strategy` vs `effective_strategy` (they
differ on a hand-over) · `justification` (**JSONB**, the sealed
`ClassificationJustification` from batch 1) · `decision_reason` ·
`chosen_tier` / `chosen_model_id` · `routing_latency_ms` (the decision only,
excluding the LLM call) · `conformal_set` / `conformal_alpha` (v2 batch 3) ·
`escalated_to` (v2 batch 4).

`decision_reason` ∈ `MATCH` · `AMBIGUOUS_ESCALATED` · `CLIENT_PINNED` ·
`BELOW_THRESHOLD_FALLBACK` · `ERROR_FALLBACK` · `NO_MODEL_FOR_TIER`.

`cache_decision`: `outcome` (`HIT` \| `MISS` \| `BYPASS` \| `ERROR`) ·
`similarity_score` and `runner_up_score` (the implicit margin) · `threshold` ·
`matched_entry_id` / `matched_entry_age_seconds` · `origin_correlation_id` ·
`embedding_model` · `conformal_status` (v2 batch 3).

Six properties worth knowing:

- **A cache hit has no routing decision.** The cache runs upstream of the
  router, so a hit short-circuits before any routing happens. That asymmetry is
  the point: it is where the trace used to be blind.
- **`origin_correlation_id` closes the loop.** It is the correlation id of the
  request that *wrote* the served entry, stamped into the vector-store metadata
  at write time, so a hit leads back to the routing decision behind the answer.
- **`prompt_hash` is not comparable to `request_log.prompt_hash`.** This one
  covers the classified user text, that one covers every message. Join on the
  correlation id, never on the hash.
- **The conformal columns are null when no calibration applied**, which is not
  the same as an empty set. `conformal_set` null means the decision was taken at
  a fixed threshold; `conformal_set` empty means a calibration applied and
  nothing qualified. `cache_decision.conformal_status` carries the same
  distinction explicitly (`EMPTY_SET` vs `NOT_CALIBRATED` vs
  `STALE_CALIBRATION`), which is what separates a deliberate refusal
  (`AMBIGUOUS`) from a plain miss.
- **`escalated_to` is null for every strategy but the cascade**, which is what
  makes the escalation rate countable from this table alone:
  `count(*) FILTER (WHERE escalated_to = 'LLM') / count(*)` over the rows where
  it is not null. The values are the levels of `CascadeLevel`
  (`DETERMINISTIC` \| `EMBEDDING` \| `LLM`), each one more expensive than the
  last, so the column is also the cost of the decision.
- **A pinned decision has no justification at all.** `justification` is null
  exactly when `decision_reason = 'CLIENT_PINNED'` (v2 batch 4): no classifier
  ran, and `chosen_model_id` / `chosen_tier` already hold the entire
  explanation — the client asked for that model. Every other row has one, which
  is batch 1's invariant.

`routing_config_version` is a short hash of the live `RoutingConfig` (strategy,
thresholds, keywords, routes and their examples, order included). The rules are
editable in production, so without it an explanation read later would silently
describe today's rules rather than the ones that applied. Every change is logged
with its timestamp by `RoutingConfigVersionTracker`.

Retention is `gatewai.decisions.retention-days` (90 by default); a scheduled
purge drops older rows. Set `gatewai.decisions.enabled=false` to record nothing —
which stops the rows, **not** the metrics: since v2 batch 6 the same decision
objects are also published to Micrometer, from the advisor rather than from the
recorder, so switching the trace off does not blind the dashboards. See
[`observability.md`](observability.md).

## `conformal_calibration` (v2 batch 3)

One row per target (`CACHE`, `ROUTING`) — the primary key is the target, because
exactly one threshold is in force at a time: `guarantee` (what α promises) ·
`alpha` · `q_hat` · `sample_size` · `embedding_model` ·
`routing_config_version` (null for the cache, which does not depend on routes) ·
`calibrated_at`.

No history table. A decision that needs explaining carries its own `alpha` and
prediction set on its own row, so replaying it never depends on what the store
happens to hold today — the same reasoning that put `routing_config_version` on
`routing_decision`. The provenance columns are what make a calibration
detectably stale; see
[`conformal-calibration.md`](conformal-calibration.md).

## `routing_config` (v3 lot B.1)

The live routing rules, **one row, `id = 1`** — a check constraint says so at the
database level, so no code path can create a second live configuration.

| Column | Type | Notes |
|---|---|---|
| `id` | integer | PK, always `1` (`routing_config_single_row` check) |
| `revision` | bigint | bumped on **every** write; the propagation signal |
| `strategy` | varchar(32) | `heuristic` \| `embedding` \| `llm` \| `cascade` |
| `entry_length_threshold` | integer | heuristic |
| `premium_length_threshold` | integer | heuristic |
| `premium_keywords` | jsonb | array of strings |
| `route_similarity_threshold` | float8 | embedding |
| `routes` | jsonb | ordered array of `{name, tier, examples}` |
| `cascade_margin_band` | float8 | written **independently** of the rules |
| `updated_at` | instant | last write |

Two design points that are easy to get wrong:

- **`revision` is not the identity of the rules.** `routing_config_version` is —
  a hash of the content, and the thing a `routing_decision` row carries.
  `revision` only answers "have I already read this?", which is why it moves even
  when a write stores identical values.
- **The band and the rules are written by separate statements.** A node's copy
  can be a poll interval stale, so writing the whole row from that copy would let
  a rules edit on one replica silently revert a band edit made on another. Each
  write touches only its own columns, under `SELECT … FOR UPDATE`.

No history table, for the reason `conformal_calibration` has none: a decision
that needs explaining already carries the version it was taken under. Ordering
inside `routes` is preserved on the round trip because route order decides ties
and `RoutingConfigVersion` hashes it.

The row is **not** seeded by the migration. The defaults live in
`ClassifierProperties` / `application.properties`, and duplicating them in SQL
would let the two drift; the first node to start inserts them with
`ON CONFLICT (id) DO NOTHING`, so whoever loses a concurrent first start adopts
the winner's configuration instead of overwriting it. See
[`clustering.md`](clustering.md).

## `deferred_job` (v3 lot B.2)

The carbon-aware queue. One row per submitted async request, from `QUEUED` to
`COMPLETED`/`FAILED`.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | PK; also the job's **correlation id** in `request_log` |
| `status` | varchar(32) | `QUEUED` \| `RUNNING` \| `COMPLETED` \| `FAILED` |
| `client_id` | varchar(255) | tenant captured at submission (the worker has no scope) |
| `request` | jsonb | the LLM request — **prompt in clear text** |
| `result` | jsonb | the response once completed |
| `chosen_zone` | varchar(32) | grid zone selected at claim time; cleared on requeue |
| `error_message` | text | set when `FAILED` |
| `claimed_by` | varchar(255) | which node holds the job (`gatewai.instance-id`, else `host:pid`) |
| `lease_expires_at` | instant | when another node may take it back; cleared on a terminal status |
| `submitted_at` | instant | queue order |
| `completed_at` | instant | terminal timestamp |

One index, `(status, submitted_at)`, serving both queries that matter on its
leading column: the claim (`status = 'QUEUED'`, oldest first) and the lease sweep
(`status = 'RUNNING'`).

`claimed_by` and `lease_expires_at` are **not** in the domain `DeferredJob`: they
describe running the queue, not the job. That is also why a completion is written
column-scoped — writing the whole row from the domain record would erase which
node ran the job on the very write that finishes it.

Prompt retention: this is the second place the gateway persists prompt text (the
first is the vector cache). There is **no purge worker for it yet** — see
[`carbon-aware-dispatch.md`](carbon-aware-dispatch.md).

## `rate_limit_bucket` (v3 lot B.3)

One Bucket4j token bucket per API client, so the rate limit is the cluster's
rather than each process's. Used only when
`gatewai.ratelimit.store=postgres`; the table is created either way, so switching
a running deployment is a property change and not a migration.

| Column | Type | Notes |
|---|---|---|
| `client_id` | varchar(255) | PK — the bucket's key, the client id as-is |
| `state` | bytea | Bucket4j's serialized bucket; **deliberately opaque** |

Read with `SELECT state … WHERE client_id = ? FOR UPDATE` inside the transaction
that writes it back. No `SKIP LOCKED` here, unlike the deferred-job claim in `V7`:
two requests from one client *must* queue on the same counter — that is what makes
it one counter. Different clients take different rows and never wait on each other.

`state` is not parsed by this project. Its layout belongs to the library, and
reading those bytes from SQL would couple the schema to a Bucket4j version. One row
per client, so the table is bounded by the client count and needs no purge.

## `api_client` (auth/admin)

`ApiClientEntity` ⇄ domain `ApiClient` ⇄ `JpaApiClientAdapter`.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | PK |
| `name` | text | client name |
| `api_key_hash` | char(64) | **unique** SHA-256 of the key; raw key never stored |
| `enabled` | boolean | revoked = `false` |
| `created_at` | instant | |
| `admin` | boolean | grants `ROLE_ADMIN` |

Lookups: by `api_key_hash` (auth), plus an `adminExists()` check used by the
bootstrap seeding. See [`security.md`](security.md).

## Vector cache (pgvector)

Managed by the Spring AI pgvector `VectorStore`. Each cached answer is a
`Document(question_text, metadata)` with a **384**-dim embedding (in-process
ONNX, `paraphrase-multilingual-MiniLM-L12-v2`, v3 lot A; 768-dim
`nomic-embed-text` before it). Metadata keys (`cached_response`, `cached_model`,
`cached_*_tokens`, `created_at`, `client_id`) are documented in
[`semantic-cache.md`](semantic-cache.md).

Config (`application.properties`): `initialize-schema=true`, `dimensions=384`,
`index-type=hnsw`, `distance-type=cosine_distance`. The `vector` extension is
created by `docker/postgres/init.sql`. The index's distance must be the one the
advisor compares with — cosine, whether the threshold in force is the fixed
`0.92` or a calibrated quantile; without an index the search scans the whole
table.

## Schema management

**Flyway owns the JPA tables** (v2 batch 0.1). Migrations live in
`src/main/resources/db/migration`; Hibernate runs with
`spring.jpa.hibernate.ddl-auto=validate` and therefore only checks that the
entities match the migrated schema — it never writes DDL. A mismatch fails
startup instead of silently altering a table.

| Migration | Content |
|---|---|
| `V1__baseline.sql` | `request_log` + `api_client`, byte-for-byte what `ddl-auto=update` used to create |
| `V2__request_log_correlation_id.sql` | `request_log.correlation_id` + its index |
| `V3__decision_tables.sql` | `routing_decision` + `cache_decision` and their indexes |
| `V4__conformal_calibration.sql` | `conformal_calibration`, plus `routing_decision.conformal_set` / `conformal_alpha` and `cache_decision.conformal_status` |
| `V5__cascade_routing.sql` | `routing_decision.escalated_to` |
| `V6__routing_config.sql` | `routing_config`, the single-row live routing rules |
| `V7__deferred_job.sql` | `deferred_job`, the carbon-aware queue + its claim index |
| `V8__rate_limit_bucket.sql` | `rate_limit_bucket`, the shared Bucket4j buckets |

The `vector_store` table and the `vector` extension are **not** managed by
Flyway: Spring AI initializes them
(`spring.ai.vectorstore.pgvector.initialize-schema=true`,
`docker/postgres/init.sql`), which keeps the gateway depending on the
`VectorStore` interface rather than on pgvector DDL
([ADR 0005](adr/0005-depend-on-vectorstore-interface.md)).

Upgrading an existing deployment needs no manual step. Flyway is configured with
`baseline-on-migrate=true` and `baseline-version=0`, and `V1` is written with
`CREATE TABLE IF NOT EXISTS`, so a database created by the old `ddl-auto=update`
is baselined and replays `V1` as a no-op, keeping its rows.

### Upgrading to v3: the vector cache must be dropped (v3 lot A)

The embedding model changed width, 768 → 384. `vector_store` is **not** a Flyway
table, so no migration can widen it and none should: the cache is a cache. The
whole procedure is one statement, run **before** starting the new version:

```sql
DROP TABLE IF EXISTS vector_store;   -- Spring AI recreates it at 384 on boot
```

Nothing else is lost. `request_log`, the decision tables, `conformal_calibration`
and `api_client` are Flyway-owned and untouched — the history, the metrics and
the traces all survive. Only the cached answers go, and they refill.

**What it looks like if you skip it** (verified, not assumed):

- Startup **succeeds**. Spring AI issues `CREATE TABLE IF NOT EXISTS`, so the
  old 768-wide table stays exactly as it is and nothing complains.
- Every request still returns `200`, but the cache is **dead**: each lookup
  raises `ERROR: different vector dimensions 768 and 384`, `SemanticCacheAdvisor`
  catches it, logs a `WARN` and treats it as a miss. On that path the advisor
  returns before `cacheStore`, so nothing is written either — the table does not
  even grow.
- The symptom is visible where v2 batch 2 put it: the decision is traced with
  `outcome = ERROR`, so `gatewai_cache_decisions_total{outcome="error"}` climbs
  and the hit rate sits at zero. That is the difference between a silent failure
  and a quiet one.

**A one-shot diagnostic, not a setting to leave on.**
`spring.ai.vectorstore.pgvector.schema-validation=true` turns the mismatch into a
startup failure naming both widths:

```
IllegalStateException: Actual vector dimensions is 768, required vector dimensions is 384
```

It is genuinely useful for a *pre-flight* check on an existing database. It
cannot be the default: validation runs **instead of** creation, so on a fresh
database — or right after the `DROP` above — the same flag fails startup with
`Table vector_store does not exist in schema public`. Turn it on to check, turn
it off to run.

The calibrations stale themselves on the same change (`embedding_model` on
`conformal_calibration`) and the evaluation fixtures refuse to replay, which is
[`conformal-calibration.md`](conformal-calibration.md) and
[`evaluation.md`](evaluation.md) doing their jobs rather than three separate
problems.
