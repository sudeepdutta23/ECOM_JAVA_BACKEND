-- =====================================================================
-- V8: Organisation invitations (Phase 4, step 4.3)
--
-- Nobody becomes a member without consent: a member with MEMBER_MANAGE invites an email address,
-- the invitee receives a single-use link, and only the signed-in account with that email can
-- accept it. Like account_tokens, only the SHA-256 hash of the link token is stored.
-- =====================================================================

CREATE TABLE organization_invitations (
    id              UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    -- Normalized like users.email, so the comparison at acceptance is exact.
    email           VARCHAR(254) NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,
    invited_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    accepted_at     TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,

    CONSTRAINT pk_organization_invitations PRIMARY KEY (id),
    CONSTRAINT fk_organization_invitations_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_organization_invitations_invited_by
        FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uk_organization_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_organization_invitations_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_organization_invitations_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_organization_invitations_role CHECK (role IN ('OWNER', 'MANAGER', 'STAFF')),
    CONSTRAINT ck_organization_invitations_expiry CHECK (expires_at > created_at),
    -- An invitation ends exactly one way: accepted or revoked, never both.
    CONSTRAINT ck_organization_invitations_single_outcome CHECK (accepted_at IS NULL OR revoked_at IS NULL)
);

-- At most one open invitation per address and organisation; inviting again replaces it.
-- Also serves "pending invitations of this organisation" (leading organization_id). Expiry is not
-- in the predicate: an index predicate must be immutable, and now() is not.
CREATE UNIQUE INDEX uk_organization_invitations_open
    ON organization_invitations (organization_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX idx_organization_invitations_invited_by ON organization_invitations (invited_by);

COMMENT ON TABLE organization_invitations IS 'Invitations to join an organisation. Only token hashes are stored.';
