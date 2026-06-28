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

`spring.jpa.hibernate.ddl-auto=update` creates/updates the JPA tables, and the
vector store initializes its own schema. Fine for a portfolio/MVP; a production
setup would use versioned migrations (Flyway/Liquibase) instead.
