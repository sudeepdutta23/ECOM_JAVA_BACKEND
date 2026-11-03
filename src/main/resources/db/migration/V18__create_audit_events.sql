-- =====================================================================
-- V18: Audit log (Phase 9)
--
-- Who did what to which account, organisation, role or category, and when. Append-only: a
-- trigger refuses UPDATE, DELETE and TRUNCATE, so neither a bug nor an attacker holding the
-- application's database account can rewrite history through it.
--
-- IDs only, never personal data: an erasure leaves the entries (their IDs then point to
-- anonymous rows), and the log can be kept as long as security requires.
--
-- Design: docs/modules/AUDIT.md.
-- =====================================================================

CREATE TABLE audit_events (
    id              UUID         NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    -- Who: the signed-in account, or NULL for the system itself (bootstrap, jobs).
    actor_id        UUID,
    action          VARCHAR(60)  NOT NULL,
    target_type     VARCHAR(30)  NOT NULL,
    target_id       UUID,
    organization_id UUID,
    detail          VARCHAR(500),
    -- Correlates the entry with the application log of the same request.
    request_id      VARCHAR(100),
    -- The client address: security-relevant, and personal data, kept for the audit retention period.
    client_ip       VARCHAR(45),

    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    -- No foreign keys on purpose: an entry must outlive the account, organisation or role it names.
    CONSTRAINT ck_audit_events_action CHECK (action ~ '^[a-z]+(\.[a-z-]+)+$'),
    CONSTRAINT ck_audit_events_target_type CHECK (target_type ~ '^[a-z-]+$')
);

COMMENT ON TABLE audit_events IS 'Append-only audit log. Owned by the audit module. IDs only, no personal data except client_ip.';

CREATE FUNCTION audit_events_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only' USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER trg_audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION audit_events_append_only();
CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events FOR EACH STATEMENT EXECUTE FUNCTION audit_events_append_only();

-- Newest first, filtered by what investigations ask: an account's actions, what happened to a
-- target, everything in one organisation. UUID v7 IDs are time-ordered, so id doubles as time.
CREATE INDEX idx_audit_events_actor ON audit_events (actor_id, id);
CREATE INDEX idx_audit_events_target ON audit_events (target_id, id);
CREATE INDEX idx_audit_events_organization ON audit_events (organization_id, id) WHERE organization_id IS NOT NULL;

INSERT INTO permissions (code, description) VALUES
    ('audit:read', 'Read the audit log');

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'audit:read' FROM roles r WHERE r.code = 'ADMIN';
