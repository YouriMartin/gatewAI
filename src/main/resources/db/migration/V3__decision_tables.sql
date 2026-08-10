-- Decision tracing (v2 batch 2): why the cache answered, and why the router
-- picked the tier it did. Both join back to request_log on correlation_id.
--
-- No prompt text is stored, only a SHA-256 of it. Note that
-- routing_decision.prompt_hash covers the *classified user text*, while
-- request_log.prompt_hash covers every message of the request: the two hashes
-- are not comparable, which is exactly why the join key is the correlation id.
--
-- Conformal fields (batch 3) and the cascade's escalated_to (batch 4) are
-- deliberately absent: they arrive with the code that writes them, rather than
-- sitting here as columns nothing fills.

CREATE TABLE IF NOT EXISTS routing_decision (
    id                      uuid         NOT NULL,
    correlation_id          varchar(64),
    created_at              timestamp(6) with time zone NOT NULL,
    prompt_hash             varchar(64)  NOT NULL,
    prompt_length           integer      NOT NULL,
    embedding_model         varchar(255),
    routing_config_version  varchar(64),
    strategy                varchar(32),
    effective_strategy      varchar(32),
    justification           jsonb,
    decision_reason         varchar(32),
    chosen_tier             varchar(32),
    chosen_model_id         varchar(255),
    routing_latency_ms      bigint,
    CONSTRAINT routing_decision_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS cache_decision (
    id                         uuid         NOT NULL,
    correlation_id             varchar(64),
    created_at                 timestamp(6) with time zone NOT NULL,
    prompt_hash                varchar(64)  NOT NULL,
    outcome                    varchar(16),
    similarity_score           float8,
    runner_up_score            float8,
    threshold                  float8,
    matched_entry_id           varchar(255),
    matched_entry_age_seconds  bigint,
    origin_correlation_id      varchar(64),
    embedding_model            varchar(255),
    CONSTRAINT cache_decision_pkey PRIMARY KEY (id)
);

-- Reading a decision back starts from the correlation id (the explain API).
CREATE INDEX IF NOT EXISTS idx_routing_decision_correlation_id
    ON routing_decision (correlation_id);
CREATE INDEX IF NOT EXISTS idx_cache_decision_correlation_id
    ON cache_decision (correlation_id);

-- The retention purge deletes by age.
CREATE INDEX IF NOT EXISTS idx_routing_decision_created_at
    ON routing_decision (created_at);
CREATE INDEX IF NOT EXISTS idx_cache_decision_created_at
    ON cache_decision (created_at);
