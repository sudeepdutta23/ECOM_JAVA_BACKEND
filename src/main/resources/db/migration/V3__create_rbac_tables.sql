-- =====================================================================
-- V3: Role-based access control (Phase 2)
--
-- permissions       what can be done, e.g. 'role:read'. Defined by the CODE; seeded here.
-- roles             named bundles of permissions. Data: admins may create custom roles.
-- role_permissions  which permissions each role grants (many-to-many)
-- user_roles        which roles each user holds (many-to-many)
--
-- Key choice: permissions use their code as a NATURAL primary key, because the code is the
-- identifier the application checks ('role:read') and it never changes. Roles use a UUID
-- SURROGATE key, because they are created at runtime and their display name may change.
-- =====================================================================

-- ---------------------------------------------------------------------
-- permissions
-- ---------------------------------------------------------------------
CREATE TABLE permissions (
    code        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_permissions PRIMARY KEY (code),
    -- resource:action, lower case, e.g. 'role:read', 'order:refund'
    CONSTRAINT ck_permissions_code_format CHECK (code ~ '^[a-z]+(:[a-z]+)+$')
);

COMMENT ON TABLE permissions IS 'Permission catalogue. Rows must match the Permission enum in code.';

-- ---------------------------------------------------------------------
-- roles
-- ---------------------------------------------------------------------
CREATE TABLE roles (
    id          UUID         NOT NULL,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    system_role BOOLEAN      NOT NULL DEFAULT false,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uk_roles_code UNIQUE (code),
    -- Upper snake case, so it reads well in tokens and logs: 'ADMIN', 'ORDER_MANAGER'
    CONSTRAINT ck_roles_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT ck_roles_name_not_blank CHECK (btrim(name) <> '')
);

COMMENT ON COLUMN roles.code        IS 'Immutable identifier used in tokens; name is the editable label';
COMMENT ON COLUMN roles.system_role IS 'Seeded roles the application depends on; cannot be renamed or deleted';

-- ---------------------------------------------------------------------
-- role_permissions
-- ---------------------------------------------------------------------
CREATE TABLE role_permissions (
    role_id         UUID        NOT NULL,
    permission_code VARCHAR(64) NOT NULL,

    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    -- RESTRICT: a permission still granted by some role cannot disappear silently.
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_code) REFERENCES permissions (code) ON DELETE RESTRICT
);

-- The primary key already serves "permissions of role X" (leading column role_id).
-- This index serves the reverse, "roles granting permission X", and the RESTRICT check.
CREATE INDEX idx_role_permissions_permission_code ON role_permissions (permission_code);

-- ---------------------------------------------------------------------
-- user_roles
-- ---------------------------------------------------------------------
CREATE TABLE user_roles (
    user_id     UUID        NOT NULL,
    role_id     UUID        NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by UUID,

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- RESTRICT: deleting a role that people still hold must be a deliberate two-step action.
    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT,
    -- Who granted it, for accountability. NULL = system (registration, bootstrap, migration).
    CONSTRAINT fk_user_roles_assigned_by
        FOREIGN KEY (assigned_by) REFERENCES users (id) ON DELETE SET NULL
);

-- PK serves "roles of user X". This serves "who holds role X" (e.g. counting admins).
CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);
-- Needed so ON DELETE SET NULL does not scan the whole table when a user is deleted.
CREATE INDEX idx_user_roles_assigned_by ON user_roles (assigned_by);

-- ---------------------------------------------------------------------
-- Seed data: the catalogue the application relies on
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, description) VALUES
    ('role:read',   'View roles, permissions and the roles of a user'),
    ('role:write',  'Create, edit and delete custom roles'),
    ('role:assign', 'Grant and revoke roles for users');

-- gen_random_uuid() (built into PostgreSQL 13+) is fine for a handful of seed rows.
-- The application never refers to these IDs, only to the codes.
INSERT INTO roles (id, code, name, description, system_role) VALUES
    (gen_random_uuid(), 'CUSTOMER', 'Customer',      'Every registered user. Shops and manages their own account.', true),
    (gen_random_uuid(), 'ADMIN',    'Administrator', 'Full administrative access.', true);

-- ADMIN holds every permission that exists now.
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r CROSS JOIN permissions p WHERE r.code = 'ADMIN';

-- ---------------------------------------------------------------------
-- Backfill: every existing account becomes a CUSTOMER
-- ---------------------------------------------------------------------
-- Without this, users created before V3 would have no role at all.
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u CROSS JOIN roles r WHERE r.code = 'CUSTOMER';
