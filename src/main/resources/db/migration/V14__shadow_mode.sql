-- Shadow mode per client (v4 D.1).
--
--   api_client.mode      ENFORCE (today's behaviour) | SHADOW: the client
--                        receives the upstream answer of the model it named,
--                        unchanged, and the cache and the router decide off the
--                        request path.
--   mode_changed_at/_by  the last change of mode and the id of the admin client
--                        that made it; NULL when the mode was never changed.
--                        Every change is also logged at INFO.
--
--   <decision>.mode      the mode of the client a decision was taken for. A
--                        SHADOW row is what the gateway would have done, never
--                        what the client received. Rows written before this
--                        migration were all enforced.

ALTER TABLE api_client
    ADD COLUMN IF NOT EXISTS mode varchar(16) NOT NULL DEFAULT 'ENFORCE',
    ADD COLUMN IF NOT EXISTS mode_changed_at timestamp(6) with time zone,
    ADD COLUMN IF NOT EXISTS mode_changed_by varchar(64);

ALTER TABLE cache_decision
    ADD COLUMN IF NOT EXISTS mode varchar(16) NOT NULL DEFAULT 'ENFORCE';
ALTER TABLE routing_decision
    ADD COLUMN IF NOT EXISTS mode varchar(16) NOT NULL DEFAULT 'ENFORCE';

-- The defaults only describe the rows above; every new decision states its own.
ALTER TABLE cache_decision ALTER COLUMN mode DROP DEFAULT;
ALTER TABLE routing_decision ALTER COLUMN mode DROP DEFAULT;
