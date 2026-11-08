-- Would the cached answer have been right? (v4 D.2)
--
-- One row per shadow would-hit: how the answer the cache would have served
-- compares with the one the client actually received. Numbers and labels only:
-- both answers are compared in memory and discarded, so this table has no text
-- column, by design and by test (WouldHitAssessmentSchemaTest).
--
--   answer_similarity     cosine of the two answers under the in-process
--                         embedding model; NULL when it could not be computed
--   beyond_window         either answer is longer than the embedding window
--                         (128 tokens for the default model): the similarity
--                         only covers its opening
--   judge_outcome         NOT_SAMPLED | JUDGED | FAILED
--   judge_verdict         YES | NO | UNSURE when JUDGED, else NULL
--   judge_model_id        the registry model that judged; NULL when not sampled
--   rubric_version        the rubric it judged under; NULL when not sampled
--   judge_correlation_id  the judge call's own request_log row (client
--                         gatewai:shadow-judge); NULL when no call was made
--
-- Joins the shadow request's cache_decision and request_log on correlation_id,
-- and is purged with the decision tables.

CREATE TABLE IF NOT EXISTS would_hit_assessment (
    id                    uuid         NOT NULL,
    correlation_id        varchar(64),
    client_id             varchar(255),
    created_at            timestamp(6) with time zone NOT NULL,
    answer_similarity     float8,
    beyond_window         boolean      NOT NULL,
    embedding_model       varchar(255),
    judge_outcome         varchar(16)  NOT NULL,
    judge_verdict         varchar(16),
    judge_model_id        varchar(255),
    rubric_version        varchar(32),
    judge_correlation_id  varchar(64),
    CONSTRAINT would_hit_assessment_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_would_hit_assessment_created_at
    ON would_hit_assessment (created_at);
CREATE INDEX IF NOT EXISTS idx_would_hit_assessment_correlation_id
    ON would_hit_assessment (correlation_id);
