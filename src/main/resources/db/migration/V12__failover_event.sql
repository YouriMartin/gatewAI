-- Ordered failover inside a tier (v4 B.5, ADR 0017).
--
-- One row per failed attempt of a request that was allowed to fail over: the
-- registry model that failed, the next one tried (null when nothing was left,
-- and the client then received this failure), and why. routing_decision keeps
-- where the request was sent first, request_log the model that answered; this
-- table is what happened in between. Joins on correlation_id, like the other
-- decision tables, and is purged with them.
--
--   path             CHAIN | PASS_THROUGH
--   cause            CONNECTION_FAILED | TIMEOUT | RATE_LIMITED | SERVER_ERROR
--   upstream_status  the provider's HTTP status; null when it never answered

CREATE TABLE IF NOT EXISTS failover_event (
    id               uuid         NOT NULL,
    correlation_id   varchar(64),
    created_at       timestamp(6) with time zone NOT NULL,
    path             varchar(16)  NOT NULL,
    tier             varchar(32)  NOT NULL,
    from_model_id    varchar(255) NOT NULL,
    to_model_id      varchar(255),
    cause            varchar(32)  NOT NULL,
    upstream_status  integer,
    CONSTRAINT failover_event_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_failover_event_correlation_id
    ON failover_event (correlation_id);

-- The retention purge deletes by age.
CREATE INDEX IF NOT EXISTS idx_failover_event_created_at
    ON failover_event (created_at);
