-- =====================================================================
-- V7: Organisations (Phase 4): marketplace sellers and their members
--
-- 1. organizations: one seller on the marketplace (the tenant)
-- 2. organization_members: who works for which seller, with one organisation role each
-- 3. Platform permissions org:read and org:manage, granted to ADMIN
--
-- Design: docs/modules/ORGANIZATIONS.md, ADR-012. Invitations follow in step 4.3 (V8).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Organisations
-- ---------------------------------------------------------------------
CREATE TABLE organizations (
    id                UUID         NOT NULL,
    -- Public handle in storefront URLs (/sellers/{slug}). Immutable, so links never break.
    slug              VARCHAR(50)  NOT NULL,
    name              VARCHAR(100) NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    status_reason     VARCHAR(500),
    status_changed_at TIMESTAMPTZ,
    status_changed_by UUID,
    created_by        UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_organizations PRIMARY KEY (id),
    CONSTRAINT uk_organizations_slug UNIQUE (slug),
    -- 3 to 50 characters: lower-case letters, digits and hyphens, no hyphen at either end.
    -- Lower-case only, so "Acme" and "acme" can never be two different sellers.
    CONSTRAINT ck_organizations_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$'),
    CONSTRAINT ck_organizations_name_trimmed CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT ck_organizations_status
        CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_organizations_suspension_has_reason
        CHECK (status <> 'SUSPENDED' OR (status_reason IS NOT NULL AND btrim(status_reason) <> '')),
    -- SET NULL: erasing or deleting a user must never delete, or block, a seller and its history.
    CONSTRAINT fk_organizations_status_changed_by
        FOREIGN KEY (status_changed_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_organizations_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);

COMMENT ON TABLE  organizations               IS 'Marketplace sellers (tenants). Owned by the org module.';
COMMENT ON COLUMN organizations.status_reason IS 'Internal note for the latest status change. Never shown to members or logged.';

-- Platform approval queue and admin list: filter by status, newest first, without a sort step.
CREATE INDEX idx_organizations_status_id         ON organizations (status, id);
-- Foreign keys with ON DELETE SET NULL: without these, deleting a user scans every organisation.
CREATE INDEX idx_organizations_status_changed_by ON organizations (status_changed_by);
CREATE INDEX idx_organizations_created_by        ON organizations (created_by);

-- ---------------------------------------------------------------------
-- 2. Members
-- ---------------------------------------------------------------------
CREATE TABLE organization_members (
    organization_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    role            VARCHAR(32) NOT NULL,
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Null for the founding owner, who was not added by anyone.
    added_by        UUID,

    -- Leading organization_id: "the members of this organisation" and "how many owners does it
    -- have" (the last-owner rule) both read this index.
    CONSTRAINT pk_organization_members PRIMARY KEY (organization_id, user_id),
    CONSTRAINT fk_organization_members_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_organization_members_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_organization_members_added_by
        FOREIGN KEY (added_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_organization_members_role CHECK (role IN ('OWNER', 'MANAGER', 'STAFF'))
);

-- "My organisations", and the per-user limit on owned organisations.
CREATE INDEX idx_organization_members_user_id  ON organization_members (user_id);
CREATE INDEX idx_organization_members_added_by ON organization_members (added_by);

-- ---------------------------------------------------------------------
-- 3. Platform permissions
-- ---------------------------------------------------------------------
-- Platform staff, not sellers: sellers act through their organisation role, never through these.
INSERT INTO permissions (code, description) VALUES
    ('org:read',   'View any organisation and the approval queue'),
    ('org:manage', 'Approve, suspend, reactivate and close any organisation');

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('org:read', 'org:manage');
