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
`Document(question_text, metadata)` with a 768-dim embedding
(`nomic-embed-text`). Metadata keys (`cached_response`, `cached_model`,
`cached_*_tokens`, `created_at`, `client_id`) are documented in
[`semantic-cache.md`](semantic-cache.md).

Config (`application.properties`): `initialize-schema=true`, `dimensions=768`,
`index-type=hnsw`, `distance-type=cosine_distance`. The `vector` extension is
created by `docker/postgres/init.sql`. The HNSW + cosine index must match the
`0.92` similarity threshold; without an index the search scans the whole table.

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
