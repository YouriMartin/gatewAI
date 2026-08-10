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
