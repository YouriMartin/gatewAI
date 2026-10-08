-- Conversation-sticky routing (v4 A.3, ADR 0015).
--
-- A conversation keeps its model unless a later turn needs a higher tier. Chat
-- Completions is stateless, so a conversation is recognised by the fingerprint
-- of its opening -- SHA-256 over the system messages before the first user
-- message, the first user message and the first answer -- and this table holds
-- the model each one runs on. Hashes and ids only: no prompt or answer text.
--
-- Shared by every node, so a conversation started on one is sticky on the
-- others. Rows are purged once last_seen_at is older than
-- gatewai.routing.conversation-ttl (24 h by default), by one node at a time.

CREATE TABLE IF NOT EXISTS conversation_affinity (
    fingerprint   varchar(64)  NOT NULL,
    model_id      varchar(255) NOT NULL,
    tier          varchar(32)  NOT NULL,
    created_at    timestamp(6) with time zone NOT NULL,
    last_seen_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT conversation_affinity_pkey PRIMARY KEY (fingerprint)
);

-- The retention purge deletes by last use.
CREATE INDEX IF NOT EXISTS idx_conversation_affinity_last_seen_at
    ON conversation_affinity (last_seen_at);

-- Each routing decision says how its conversation bore on it, so the three
-- cases are told apart from this table alone:
--
--   conversation_routing      STICKY | UPGRADED | FIRST_TURN_FLOOR; null on a
--                             first turn or a pinned request
--   classified_tier           the last user turn's own tier, before the floor
--                             (chosen_tier is the tier the request was sent to)
--   conversation_fingerprint  the opening's hash, to follow one conversation
--
-- Rows written before this migration keep null in all three.

ALTER TABLE routing_decision
    ADD COLUMN IF NOT EXISTS conversation_routing     varchar(32),
    ADD COLUMN IF NOT EXISTS classified_tier          varchar(32),
    ADD COLUMN IF NOT EXISTS conversation_fingerprint varchar(64);
