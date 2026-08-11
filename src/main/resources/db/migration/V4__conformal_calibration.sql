-- Conformal calibration (v2 batch 3).
--
-- One row per target: there is exactly one threshold in force at a time, and a
-- decision that needs explaining carries its own alpha and prediction set on its
-- own row, so no history is needed here to replay one.
--
-- The columns reserved by V3 arrive with the code that writes them:
-- routing_decision.conformal_set / conformal_alpha, and
-- cache_decision.conformal_status.

CREATE TABLE IF NOT EXISTS conformal_calibration (
    target                  varchar(32)  NOT NULL,
    guarantee               varchar(32)  NOT NULL,
    alpha                   float8       NOT NULL,
    q_hat                   float8       NOT NULL,
    sample_size             integer      NOT NULL,
    embedding_model         varchar(255),
    routing_config_version  varchar(64),
    calibrated_at           timestamp(6) with time zone NOT NULL,
    CONSTRAINT conformal_calibration_pkey PRIMARY KEY (target)
);

-- The prediction set a routing decision was taken under, and the risk level it
-- was taken at. Null whenever no calibration was in force, which is exactly how
-- a degraded decision stays distinguishable from a calibrated one.
ALTER TABLE routing_decision
    ADD COLUMN IF NOT EXISTS conformal_set   varchar(255),
    ADD COLUMN IF NOT EXISTS conformal_alpha float8;

-- Whether the cache's prediction set was empty, a singleton, or ambiguous --
-- and, when it is neither, why the fixed threshold decided instead.
ALTER TABLE cache_decision
    ADD COLUMN IF NOT EXISTS conformal_status varchar(32);
