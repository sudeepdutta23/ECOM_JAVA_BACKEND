-- =====================================================================
-- V10: Personal profiles (Phase 5, step 5.1)
--
-- One optional row per account: how to address the person and how to reach them. Login data
-- (email, password) stays in users and belongs to auth; this table belongs to the profile module.
--
-- Design: docs/modules/PROFILE.md.
-- =====================================================================

CREATE TABLE user_profiles (
    -- The account's ID is the key: at most one profile per account, found without an extra index.
    user_id          UUID         NOT NULL,
    given_name       VARCHAR(100),
    family_name      VARCHAR(100),
    -- E.164: '+', country code, subscriber number, at most 15 digits. Not verified (no SMS yet),
    -- so it is contact data only and never used to sign in or to recover an account.
    phone_number     VARCHAR(16),
    -- BCP 47 language tag, language and optional region: 'de', 'en-GB'.
    preferred_locale VARCHAR(16),
    -- IANA zone ID such as 'Europe/Berlin'. Checked against the JVM's zone list by the application.
    time_zone        VARCHAR(64),
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_user_profiles PRIMARY KEY (user_id),
    -- CASCADE: deleting an account deletes its profile. Erasure keeps the users row, so the
    -- profile module deletes the profile itself, in the erasure's transaction (ProfileEraser).
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- Empty strings are stored as NULL, so "not given" has exactly one representation.
    CONSTRAINT ck_user_profiles_given_name_trimmed
        CHECK (given_name IS NULL OR (given_name = btrim(given_name) AND given_name <> '')),
    CONSTRAINT ck_user_profiles_family_name_trimmed
        CHECK (family_name IS NULL OR (family_name = btrim(family_name) AND family_name <> '')),
    CONSTRAINT ck_user_profiles_phone_e164 CHECK (phone_number ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT ck_user_profiles_locale_format CHECK (preferred_locale ~ '^[a-z]{2,3}(-[A-Z]{2})?$'),
    CONSTRAINT ck_user_profiles_time_zone_trimmed CHECK (time_zone = btrim(time_zone) AND time_zone <> '')
);

COMMENT ON TABLE  user_profiles              IS 'Personal profile of an account. Owned by the profile module. Personal data: never logged.';
COMMENT ON COLUMN user_profiles.phone_number IS 'E.164, unverified. Contact data only, never an authentication factor.';
