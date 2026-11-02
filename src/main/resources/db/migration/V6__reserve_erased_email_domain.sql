-- =====================================================================
-- V6: Erasure placeholders cannot be claimed (Phase 3 review)
--
-- An erased account's email becomes erased-<id>@erased.invalid (V5). If anyone else could hold
-- that address first, the erasure would fail on uk_users_email: a way to block someone's
-- right to erasure. Registration refuses every .invalid address; this CHECK makes the
-- placeholder domain impossible for any account that is not erased, whatever the code does.
--
-- A new migration instead of editing V5: a migration that may already have run anywhere is
-- never changed (Flyway would refuse the checksum, and databases would silently differ).
-- =====================================================================

ALTER TABLE users ADD CONSTRAINT ck_users_erased_domain_reserved
    CHECK (status = 'ERASED' OR email NOT LIKE '%@erased.invalid');
