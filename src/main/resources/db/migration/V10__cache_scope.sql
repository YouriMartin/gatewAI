-- Scope the semantic cache by conversation context (v4 A.2, ADR 0014).
--
-- The cache now compares the last user turn only inside an identical context,
-- and every decision records which context that was -- as a hash, never text --
-- plus why it was not a plain lookup:
--
--   reason       EMPTY_PROMPT | HISTORY_TOO_LONG (BYPASS), EXACT_MATCH_ONLY
--                (a last turn past the embedding window), MAX_TOKENS (a
--                candidate longer than the request allows). Null on a plain
--                HIT or MISS. v4 B.3 adds PASSTHROUGH_<feature>.
--   cache_scope  SHA-256 of the conversation scope the lookup ran in.
--
-- Rows written before this migration keep null in both: they were decided with
-- no scope, and nothing is back-filled.
--
-- The vector store needs no migration: the scope is document metadata, and an
-- entry written before ADR 0014 has none, so it can never match a lookup again.
-- To reclaim the space (optional):
--
--   DELETE FROM vector_store WHERE NOT (metadata::jsonb ? 'cache_scope');

ALTER TABLE cache_decision
    ADD COLUMN IF NOT EXISTS reason      varchar(64),
    ADD COLUMN IF NOT EXISTS cache_scope varchar(64);
