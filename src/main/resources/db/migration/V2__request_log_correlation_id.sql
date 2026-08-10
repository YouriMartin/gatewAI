-- Correlation id (v2 batch 0.3). One id per inbound request, generated at
-- ingress and carried through the advisor chain in RequestContext. It is the
-- join key the v2 decision tables (routing + cache) will reference, so the
-- carbon/cost record and the decisions that produced it can be read together.
--
-- Nullable: rows written before this migration have none, and the deferred
-- dispatch path may still produce logs outside an HTTP request.

ALTER TABLE request_log ADD COLUMN IF NOT EXISTS correlation_id varchar(64);

CREATE INDEX IF NOT EXISTS idx_request_log_correlation_id
    ON request_log (correlation_id);
