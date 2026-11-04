-- =====================================================================
-- V22: Email outbox (Phase 13)
--
-- An email written here commits with the change that caused it, and is sent after commit by
-- EmailOutboxRelay: a crash between commit and send no longer loses it. A sent row is deleted.
-- Emails carrying one-time tokens never come here (the raw token would be stored).
--
-- Design: docs/modules/EVENTS.md, ADR-015.
-- =====================================================================

CREATE TABLE email_outbox (
    id              UUID          NOT NULL,
    recipient       VARCHAR(320)  NOT NULL,
    subject         VARCHAR(255)  NOT NULL,
    body            TEXT          NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    -- Sending attempts started; a claimed row counts its attempt before the send.
    attempts        INT           NOT NULL DEFAULT 0,
    -- When the row may be claimed: now for a new row, the lease end while one server sends it,
    -- the backoff after a failure.
    next_attempt_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- The exception class of the last failure, never its message (it can quote the address).
    last_error      VARCHAR(200),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_email_outbox PRIMARY KEY (id),
    CONSTRAINT ck_email_outbox_status CHECK (status IN ('PENDING', 'FAILED')),
    CONSTRAINT ck_email_outbox_attempts CHECK (attempts >= 0)
);

COMMENT ON TABLE email_outbox IS 'Emails waiting to be sent. Owned by common.mail.';

-- What the relay claims: due rows only, oldest first. Sent rows are deleted, so it stays small.
CREATE INDEX idx_email_outbox_due ON email_outbox (next_attempt_at) WHERE status = 'PENDING';

-- Account erasure deletes whatever is still waiting for that address.
CREATE INDEX idx_email_outbox_recipient ON email_outbox (lower(recipient));
