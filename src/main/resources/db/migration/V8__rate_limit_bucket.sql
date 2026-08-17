-- Shared rate-limit buckets (v3 lot B.3).
--
-- Bucket4j held its token buckets in a ConcurrentHashMap, so the limit was each
-- process's rather than the cluster's: two replicas behind a load balancer let a
-- client through 120 times a minute on a 60/min limit, and neither node was
-- doing anything wrong. The buckets now live here, read with
--
--   SELECT state FROM rate_limit_bucket WHERE client_id = ? FOR UPDATE
--
-- inside the transaction that writes the new state back. No SKIP LOCKED, unlike
-- the deferred-job claim in V7: two requests from the same client MUST queue on
-- the same counter, that is what makes it one counter. Requests from different
-- clients touch different rows and never wait on each other.
--
-- Row locking rather than pg_advisory_xact_lock: advisory locks key on a bigint,
-- so a client id would have to be hashed to 64 bits, and a collision there would
-- silently merge two tenants' quotas. Correctness beats the one saved index
-- lookup.
--
-- `state` is Bucket4j's own serialized bucket, deliberately opaque: the schema of
-- those bytes belongs to the library, and reading them from SQL would couple this
-- project to a version of it. One row per API client, so the table is bounded by
-- the number of clients and needs no purge. Left NULL by the insert Bucket4j does
-- on first use, then written on every consumption.
--
-- Only used when gatewai.ratelimit.store=postgres; the table is created either
-- way so switching a running deployment is a property change, not a migration.

CREATE TABLE IF NOT EXISTS rate_limit_bucket (
    client_id  varchar(255) NOT NULL,
    state      bytea,
    CONSTRAINT rate_limit_bucket_pkey PRIMARY KEY (client_id)
);
