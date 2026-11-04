-- =====================================================================
-- V20: Job locks (Phase 12)
--
-- One row per scheduled job. A server runs a job only while it holds the row (lock_until in
-- the future), so with N servers each job still runs once per schedule. Written by ShedLock;
-- the time comes from the database, so servers with skewed clocks agree.
--
-- Design: docs/modules/JOBS.md.
-- =====================================================================

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,

    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS 'Locks of scheduled jobs (ShedLock). No business data.';
