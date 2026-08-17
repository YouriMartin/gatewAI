-- Persisted carbon-aware deferred jobs (v3 lot B.2).
--
-- Jobs lived in a ConcurrentHashMap: they were lost on restart, and a job
-- submitted on one replica was invisible to every other one -- including the
-- worker that was supposed to run it. A client polling
-- GET /v1/chat/completions/async/{id} through a load balancer got a 404 half the
-- time, for a job that existed.
--
-- The queue is now this table, and the claim is what makes N workers safe:
--
--   SELECT ... WHERE status = 'QUEUED' ORDER BY submitted_at
--     FOR UPDATE SKIP LOCKED LIMIT :batch
--
-- inside the transaction that flips those rows to RUNNING. A second worker
-- running the same statement concurrently skips the locked rows rather than
-- waiting for them, so each job is claimed exactly once and neither worker
-- blocks.
--
-- claimed_by + lease_expires_at are the crash-recovery rule. A claim is a lease,
-- not a promise: if the node holding it dies, nothing releases the RUNNING row,
-- so every dispatch tick first requeues the rows whose lease has expired. That
-- recovers jobs stranded by a node that never comes back, which a
-- requeue-on-startup rule would not. The price is that a job whose execution
-- legitimately outlives the lease can be run twice -- see
-- docs/technical/carbon-aware-dispatch.md for why the lease default is sized well
-- above a completion, and why at-least-once is the honest guarantee here.

CREATE TABLE IF NOT EXISTS deferred_job (
    id                uuid         NOT NULL,
    status            varchar(32)  NOT NULL,
    client_id         varchar(255),
    request           jsonb        NOT NULL,
    result            jsonb,
    chosen_zone       varchar(32),
    error_message     text,
    claimed_by        varchar(255),
    lease_expires_at  timestamp(6) with time zone,
    submitted_at      timestamp(6) with time zone NOT NULL,
    completed_at      timestamp(6) with time zone,
    CONSTRAINT deferred_job_pkey PRIMARY KEY (id)
);

-- Serves both queries that matter, on its leading column: the claim
-- (status = 'QUEUED', oldest first) and the lease sweep (status = 'RUNNING',
-- lease_expires_at in the past).
CREATE INDEX IF NOT EXISTS idx_deferred_job_status_submitted_at
    ON deferred_job (status, submitted_at);
