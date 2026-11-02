-- =====================================================================
-- V5: Account erasure by anonymisation (Phase 3, step 3.5)
--
-- 1. Permission user:erase, granted to ADMIN
-- 2. Account status ERASED, and a CHECK that an erased row holds no personal data
--
-- The row itself stays: later tables (orders, invoices, audit entries) reference users.id, and
-- some of them must be kept by law. Sessions, email links and role assignments are deleted by
-- the application in the same transaction; see docs/modules/USERS.md section 9.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Permission
-- ---------------------------------------------------------------------
-- Separate from user:manage: suspension can be undone, erasure cannot. A support team can hold
-- user:manage without being able to destroy accounts.
INSERT INTO permissions (code, description) VALUES
    ('user:erase', 'Irreversibly anonymise user accounts on request');

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'user:erase' FROM roles r WHERE r.code = 'ADMIN';

-- ---------------------------------------------------------------------
-- 2. Status
-- ---------------------------------------------------------------------
ALTER TABLE users DROP CONSTRAINT ck_users_status;
ALTER TABLE users ADD CONSTRAINT ck_users_status
    CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DEACTIVATED', 'SUSPENDED', 'ERASED'));

-- The database, not only the code, guarantees what "erased" means. A bug or a manual UPDATE that
-- sets ERASED but leaves the real email behind fails here instead of silently keeping the data.
-- The placeholder contains the row's own ID, so it is unique (uk_users_email) and lower-case
-- (ck_users_email_normalized), and the .invalid top-level domain (RFC 2606) can never receive mail.
-- '{erased}' is not a valid hash for any encoder, so no password can ever match it.
ALTER TABLE users ADD CONSTRAINT ck_users_erased_is_anonymous CHECK (
    status <> 'ERASED' OR (
        email = 'erased-' || id::text || '@erased.invalid'
        AND password_hash = '{erased}'
        AND email_verified_at IS NULL
        AND last_login_at IS NULL
        AND locked_until IS NULL
        AND status_reason IS NULL));

COMMENT ON CONSTRAINT ck_users_erased_is_anonymous ON users IS
    'An erased account keeps only its ID, timestamps and status. See docs/modules/USERS.md.';
