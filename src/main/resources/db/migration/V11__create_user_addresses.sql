-- =====================================================================
-- V11: Address book (Phase 5, step 5.2)
--
-- A person's saved shipping and billing addresses, at most one default of each kind.
-- Orders (Phase 6) will COPY an address when placed, never reference it: editing or deleting
-- an address must never change where a past order went or what its invoice says.
--
-- Design: docs/modules/PROFILE.md.
-- =====================================================================

CREATE TABLE user_addresses (
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    -- The owner's own name for it: 'Home', 'Office'. Never printed on a parcel.
    label            VARCHAR(50),
    recipient_name   VARCHAR(100) NOT NULL,
    company          VARCHAR(100),
    line1            VARCHAR(200) NOT NULL,
    line2            VARCHAR(200),
    city             VARCHAR(100) NOT NULL,
    -- State, province or county; required in some countries (checked by the application).
    region           VARCHAR(100),
    -- Upper case, single spaces. The format per country is checked by the application.
    postal_code      VARCHAR(20),
    -- ISO 3166-1 alpha-2. VARCHAR, not CHAR, so Hibernate's schema validation maps it to String.
    country_code     VARCHAR(2)   NOT NULL,
    -- Contact for the courier, E.164 like user_profiles.phone_number.
    phone_number     VARCHAR(16),
    default_shipping BOOLEAN      NOT NULL DEFAULT false,
    default_billing  BOOLEAN      NOT NULL DEFAULT false,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_user_addresses PRIMARY KEY (id),
    -- CASCADE: deleting an account deletes its addresses. Erasure deletes them explicitly (ProfileEraser).
    CONSTRAINT fk_user_addresses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_addresses_label_trimmed
        CHECK (label = btrim(label) AND label <> ''),
    CONSTRAINT ck_user_addresses_recipient_name_trimmed
        CHECK (recipient_name = btrim(recipient_name) AND recipient_name <> ''),
    CONSTRAINT ck_user_addresses_company_trimmed
        CHECK (company = btrim(company) AND company <> ''),
    CONSTRAINT ck_user_addresses_line1_trimmed
        CHECK (line1 = btrim(line1) AND line1 <> ''),
    CONSTRAINT ck_user_addresses_line2_trimmed
        CHECK (line2 = btrim(line2) AND line2 <> ''),
    CONSTRAINT ck_user_addresses_city_trimmed
        CHECK (city = btrim(city) AND city <> ''),
    CONSTRAINT ck_user_addresses_region_trimmed
        CHECK (region = btrim(region) AND region <> ''),
    CONSTRAINT ck_user_addresses_postal_code_format
        CHECK (postal_code ~ '^[A-Z0-9]([A-Z0-9 -]{0,18}[A-Z0-9])?$'),
    CONSTRAINT ck_user_addresses_country_code_format CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_user_addresses_phone_e164 CHECK (phone_number ~ '^\+[1-9][0-9]{6,14}$')
);

COMMENT ON TABLE user_addresses IS 'Address book of an account. Owned by the profile module. Personal data: never logged.';

-- "My addresses", newest first (UUID v7 IDs are time-ordered), the per-account limit, and erasure.
CREATE INDEX idx_user_addresses_user_id ON user_addresses (user_id, id);

-- At most one default of each kind per account. Partial: only the few default rows are indexed,
-- and finding the current default (to clear it) reads this index.
CREATE UNIQUE INDEX uk_user_addresses_default_shipping ON user_addresses (user_id) WHERE default_shipping;
CREATE UNIQUE INDEX uk_user_addresses_default_billing  ON user_addresses (user_id) WHERE default_billing;
