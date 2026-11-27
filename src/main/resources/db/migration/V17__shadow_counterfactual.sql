-- What a shadow request would have cost (v4 D.4, ADR 0020).
--
-- request_log.mode: ENFORCE | SHADOW, the mode of the client the request was
-- served for, as the decision tables have carried since V14. A SHADOW row is a
-- request served by pass-through to a shadow client: what it actually paid.
-- Rows written before this migration read ENFORCE; a shadow request among them
-- cannot be told apart, and a report covering them says it does not know.
--
-- shadow_counterfactual: one row per evaluated shadow request, priced by the
-- replay when it decided, by the same accounting code as request_log, and
-- self-describing like it (v3 C.5): a report of a past period re-derives from
-- these rows, never from today's prices or regions.
--
--   outcome          WOULD_HIT (served from the cache: no inference, no cost) |
--                    ROUTED (a miss, sent to the model the router chose) |
--                    PASSED_THROUGH (the chain cannot honour it: the actual)
--   model            the model that would have answered; NULL exactly on a
--                    WOULD_HIT
--   *_tokens         the usage it was priced with: the usage of the answer the
--                    client received, since the routed model never ran
--                    (modelled, not measured)
--   cost .. pue      as in request_log; no avoided figures, which compare a
--                    served request with the premium baseline
--
-- Joins request_log, cache_decision and routing_decision on correlation_id, and
-- is purged with the decision tables.

ALTER TABLE request_log
    ADD COLUMN IF NOT EXISTS mode varchar(16) NOT NULL DEFAULT 'ENFORCE';
ALTER TABLE request_log ALTER COLUMN mode DROP DEFAULT;
ALTER TABLE request_log
    ADD CONSTRAINT request_log_mode_check CHECK (mode IN ('ENFORCE', 'SHADOW'));

-- The report reads one client's shadow requests over a period.
CREATE INDEX IF NOT EXISTS idx_request_log_mode_client_timestamp
    ON request_log (mode, client_id, timestamp);

CREATE TABLE IF NOT EXISTS shadow_counterfactual (
    id                        uuid         NOT NULL,
    correlation_id            varchar(64)  NOT NULL,
    client_id                 varchar(255),
    created_at                timestamp(6) with time zone NOT NULL,
    outcome                   varchar(16)  NOT NULL,
    model                     varchar(255),
    prompt_tokens             integer      NOT NULL,
    completion_tokens         integer      NOT NULL,
    total_tokens              integer      NOT NULL,
    cached_prompt_tokens      integer,
    cost                      float8       NOT NULL,
    pricing_basis             varchar(16)  NOT NULL,
    currency                  varchar(3)   NOT NULL,
    energy_kwh                float8       NOT NULL,
    grams_co2                 float8       NOT NULL,
    provider                  varchar(255),
    grid_zone                 varchar(64),
    grid_intensity_g_per_kwh  float8,
    grid_zone_source          varchar(32),
    dispatch_zone             varchar(64),
    region_provenance         varchar(16),
    energy_source             varchar(32),
    pue                       float8,
    CONSTRAINT shadow_counterfactual_pkey PRIMARY KEY (id),
    CONSTRAINT shadow_counterfactual_outcome_check
        CHECK (outcome IN ('WOULD_HIT', 'ROUTED', 'PASSED_THROUGH')),
    -- A would-hit names no model and runs no inference; anything else names one.
    CONSTRAINT shadow_counterfactual_model_check
        CHECK ((outcome = 'WOULD_HIT') = (model IS NULL)),
    CONSTRAINT shadow_counterfactual_would_hit_free
        CHECK (outcome <> 'WOULD_HIT' OR (cost = 0 AND energy_kwh = 0 AND grams_co2 = 0))
);

CREATE INDEX IF NOT EXISTS idx_shadow_counterfactual_created_at
    ON shadow_counterfactual (created_at);
CREATE INDEX IF NOT EXISTS idx_shadow_counterfactual_correlation_id
    ON shadow_counterfactual (correlation_id);
