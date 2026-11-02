-- =====================================================================
-- V4: User management (Phase 3)
--
-- 1. Permissions user:read and user:manage, granted to ADMIN
-- 2. Account status SUSPENDED, and who/when/why for the latest status change
-- 3. Refresh-token revoke reasons for admin actions
-- 4. Indexes for the admin user list
--
-- Locking note: ALTER TABLE ... ADD CONSTRAINT and CREATE INDEX lock the table against writes
-- while they run. On a small table that is milliseconds. On a large production table you would
-- instead add CHECKs as NOT VALID and VALIDATE them in a later transaction, and build indexes
-- with CREATE INDEX CONCURRENTLY in a separate, non-transactional migration.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Permissions
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, description) VALUES
    ('user:read',   'Search and view user accounts'),
    ('user:manage', 'Suspend, reactivate, unlock and sign out user accounts');

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('user:read', 'user:manage');

-- ---------------------------------------------------------------------
-- 2. Status
-- ---------------------------------------------------------------------
ALTER TABLE users DROP CONSTRAINT ck_users_status;
ALTER TABLE users ADD CONSTRAINT ck_users_status
    CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DEACTIVATED', 'SUSPENDED'));

ALTER TABLE users
    ADD COLUMN status_reason     VARCHAR(500),
    ADD COLUMN status_changed_at TIMESTAMPTZ,
    ADD COLUMN status_changed_by UUID;

-- SET NULL: deleting the administrator must not delete, or block deleting, the accounts they managed.
ALTER TABLE users ADD CONSTRAINT fk_users_status_changed_by
    FOREIGN KEY (status_changed_by) REFERENCES users (id) ON DELETE SET NULL;

-- A suspension without a reason cannot be reviewed or appealed.
ALTER TABLE users ADD CONSTRAINT ck_users_suspension_has_reason
    CHECK (status <> 'SUSPENDED' OR (status_reason IS NOT NULL AND btrim(status_reason) <> ''));

COMMENT ON COLUMN users.status_reason IS 'Internal note for the latest status change. Never shown to the owner or logged.';

-- ---------------------------------------------------------------------
-- 3. Revoke reasons
-- ---------------------------------------------------------------------
ALTER TABLE refresh_tokens DROP CONSTRAINT ck_refresh_tokens_revoke_reason;
ALTER TABLE refresh_tokens ADD CONSTRAINT ck_refresh_tokens_revoke_reason CHECK (revoke_reason IN (
    'ROTATED', 'LOGOUT', 'LOGOUT_ALL', 'REUSE_DETECTED',
    'PASSWORD_CHANGED', 'PASSWORD_RESET', 'ACCOUNT_DEACTIVATED',
    'ACCOUNT_SUSPENDED', 'ADMIN_SIGN_OUT'));

-- ---------------------------------------------------------------------
-- 4. Indexes
-- ---------------------------------------------------------------------
-- Filter by status, newest first (UUID v7: id order = creation order), without a sort step.
CREATE INDEX idx_users_status_id ON users (status, id);

-- Prefix search (LIKE 'abc%'). The V2 unique constraint's index uses the database collation,
-- which PostgreSQL cannot use for prefix matching. An index with varchar_pattern_ops compares
-- character by character, and serves BOTH equality (login, uniqueness) and prefix search.
-- So instead of adding a second index on email, the unique index is rebuilt with that operator
-- class, under the same name: duplicate-email errors still report uk_users_email.
ALTER TABLE users DROP CONSTRAINT uk_users_email;
CREATE UNIQUE INDEX uk_users_email ON users (email varchar_pattern_ops);

-- Without it, deleting a user scans all users to apply ON DELETE SET NULL.
CREATE INDEX idx_users_status_changed_by ON users (status_changed_by);
