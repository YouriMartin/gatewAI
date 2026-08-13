-- Calibrated cascade routing (v2 batch 4).
--
-- The column V3 reserved and left out on purpose now has code that writes it:
-- how far the cascade had to go before it could decide. Null for every other
-- strategy, which is what makes the escalation rate -- the cost of the cascade
-- -- countable from this table alone:
--
--   SELECT count(*) FILTER (WHERE escalated_to = 'LLM')::float
--          / nullif(count(*), 0)
--     FROM routing_decision
--    WHERE escalated_to IS NOT NULL;
--
-- Client pinning (the same batch) needs no column: it is decision_reason =
-- 'CLIENT_PINNED', with a null justification, because no classifier ran and
-- chosen_model_id already holds the model the client asked for.

ALTER TABLE routing_decision
    ADD COLUMN IF NOT EXISTS escalated_to varchar(32);
