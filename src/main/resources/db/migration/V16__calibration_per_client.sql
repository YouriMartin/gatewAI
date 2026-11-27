-- Calibrate the cache on shadow labels, per client (v4 D.3, ADR 0019).
--
-- conformal_calibration moves from one row per target to one row per
-- (target, client):
--
--   client_id        '' for the global row, else the API client the row applies
--                    to. A primary-key column cannot be NULL, hence the empty
--                    string; the domain reads it back as "no client". Only the
--                    cache has client rows, and only from a shadow fit.
--   source           LABELLED_SET (the hand-labelled cases, the only source
--                    before this migration) | SHADOW (judge-labelled would-hits)
--   judge_model      the registry model whose verdicts were fitted on
--   rubric_version   the JudgeRubric version those verdicts were given under
--   label_floor      F: the threshold in force for the scope when it was
--                    fitted; only labels recorded at or below it and scoring at
--                    or above it counted
--                    (the three above are set exactly on a SHADOW row)
--   fit_summary      what the fit read, what it excluded and why, and the
--                    counts behind the threshold (versioned JSON). It outlives
--                    the labels, which are purged with the decision tables.
--                    NULL on rows fitted before this migration.
--
-- cache_decision.calibration_scope: which calibration decided the request,
-- CLIENT (its client's own row) | GLOBAL | NONE (the fixed threshold). NULL on
-- rows written before this migration: nothing is back-filled, a decision stays
-- as it was persisted.

ALTER TABLE conformal_calibration
    ADD COLUMN IF NOT EXISTS client_id      varchar(255) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS source         varchar(16)  NOT NULL DEFAULT 'LABELLED_SET',
    ADD COLUMN IF NOT EXISTS judge_model    varchar(255),
    ADD COLUMN IF NOT EXISTS rubric_version varchar(32),
    ADD COLUMN IF NOT EXISTS label_floor    float8,
    ADD COLUMN IF NOT EXISTS fit_summary    jsonb;

-- The defaults only describe the rows above; every new row states its own.
ALTER TABLE conformal_calibration ALTER COLUMN client_id DROP DEFAULT;
ALTER TABLE conformal_calibration ALTER COLUMN source DROP DEFAULT;

ALTER TABLE conformal_calibration DROP CONSTRAINT conformal_calibration_pkey;
ALTER TABLE conformal_calibration
    ADD CONSTRAINT conformal_calibration_pkey PRIMARY KEY (target, client_id);

-- The rules of ADR 0019, held by the table as well as by the code.
ALTER TABLE conformal_calibration
    ADD CONSTRAINT conformal_calibration_source_check
        CHECK (source IN ('LABELLED_SET', 'SHADOW')),
    -- Routing is never fitted per client, nor on judge labels.
    ADD CONSTRAINT conformal_calibration_routing_global
        CHECK (target = 'CACHE' OR (client_id = '' AND source = 'LABELLED_SET')),
    -- A client row only comes from that client's shadow labels.
    ADD CONSTRAINT conformal_calibration_client_from_shadow
        CHECK (client_id = '' OR source = 'SHADOW'),
    -- A shadow row names its judge, its rubric and its floor; no other row does.
    ADD CONSTRAINT conformal_calibration_shadow_provenance
        CHECK ((source = 'SHADOW') = (judge_model IS NOT NULL)
               AND (source = 'SHADOW') = (rubric_version IS NOT NULL)
               AND (source = 'SHADOW') = (label_floor IS NOT NULL));

ALTER TABLE cache_decision
    ADD COLUMN IF NOT EXISTS calibration_scope varchar(16);
