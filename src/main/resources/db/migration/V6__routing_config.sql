-- Persisted routing configuration (v3 lot B.1).
--
-- The live routing rules were node-local: a PUT /v1/admin/routing on one
-- replica left every other replica routing on the old rules indefinitely, while
-- each kept stamping its own routing_config_version onto its decisions. Two
-- nodes could then explain the same prompt under different rules and both look
-- authoritative -- the trace lied without ever contradicting itself. The rules
-- now live here; each node keeps an in-memory copy and re-reads this row on a
-- short interval.
--
-- One row, id = 1, rather than one row per version with a `current` flag: a
-- decision that needs explaining already carries its own routing_config_version
-- (V3), so a history table here would be a second copy nothing points at. The
-- version is a hash of the rules, not a foreign key.
--
-- `revision` is the propagation signal, not the identity of the rules. It is
-- bumped on every write, including one that stores identical values, because a
-- node compares revisions to decide whether to re-read -- cheaper than
-- comparing the payload, and re-applying an identical config is a no-op
-- anyway. Identity stays routing_config_version, which is content-addressed.
--
-- cascade_margin_band lives on the same row but is written independently
-- (v2 batch 4, D26): it is not part of the config, and so not part of the
-- version, because it changes no similarity and must not invalidate a conformal
-- calibration.

CREATE TABLE IF NOT EXISTS routing_config (
    id                          integer      NOT NULL,
    revision                    bigint       NOT NULL,
    strategy                    varchar(32)  NOT NULL,
    entry_length_threshold      integer      NOT NULL,
    premium_length_threshold    integer      NOT NULL,
    premium_keywords            jsonb        NOT NULL,
    route_similarity_threshold  float8       NOT NULL,
    routes                      jsonb        NOT NULL,
    cascade_margin_band         float8       NOT NULL,
    updated_at                  timestamp(6) with time zone NOT NULL,
    CONSTRAINT routing_config_pkey PRIMARY KEY (id),
    CONSTRAINT routing_config_single_row CHECK (id = 1)
);

-- No seed row here on purpose. The defaults are the ones in ClassifierProperties
-- and application.properties -- bilingual route examples included -- and
-- duplicating them in SQL would let the two drift apart silently. The first node
-- to start inserts the row from its own configuration; whoever loses that race
-- reads the winner's row instead of overwriting it.
