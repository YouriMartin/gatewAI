-- Baseline of the schema previously created by Hibernate `ddl-auto=update`
-- (v2 batch 0.1). Mirrors RequestLogEntity and ApiClientEntity exactly, so
-- `ddl-auto=validate` passes against it.
--
-- Idempotent on purpose. Flyway is configured with baseline-on-migrate=true and
-- baseline-version=0, so this migration also runs against databases that already
-- hold these tables (existing deployments) and against fresh databases where
-- Spring AI's PgVectorStore created `vector_store` before Flyway ran — in both
-- cases the schema looks non-empty and the IF NOT EXISTS clauses make the
-- replay a no-op.
--
-- Not managed here: the `vector_store` table and the `vector` extension. They
-- stay owned by Spring AI (spring.ai.vectorstore.pgvector.initialize-schema),
-- so the gateway keeps depending on the VectorStore interface rather than on
-- pgvector DDL (ADR 0005).

CREATE TABLE IF NOT EXISTS api_client (
    id            uuid         NOT NULL,
    name          varchar(255) NOT NULL,
    api_key_hash  varchar(64)  NOT NULL,
    enabled       boolean      NOT NULL,
    created_at    timestamp(6) with time zone NOT NULL,
    admin         boolean      NOT NULL,
    CONSTRAINT api_client_pkey PRIMARY KEY (id),
    CONSTRAINT api_client_api_key_hash_key UNIQUE (api_key_hash)
);

CREATE TABLE IF NOT EXISTS request_log (
    id                 uuid         NOT NULL,
    timestamp          timestamp(6) with time zone NOT NULL,
    model              varchar(255) NOT NULL,
    prompt_hash        varchar(64)  NOT NULL,
    prompt_tokens      integer      NOT NULL,
    completion_tokens  integer      NOT NULL,
    total_tokens       integer      NOT NULL,
    latency_ms         bigint       NOT NULL,
    client_id          varchar(255),
    cost_eur           float8,
    energy_kwh         float8,
    grams_co2          float8,
    cost_avoided_eur   float8,
    grams_co2_avoided  float8,
    cache_hit          boolean,
    CONSTRAINT request_log_pkey PRIMARY KEY (id)
);

-- Every green report filters on a time window (findByTimestampBetween).
CREATE INDEX IF NOT EXISTS idx_request_log_timestamp ON request_log (timestamp);
