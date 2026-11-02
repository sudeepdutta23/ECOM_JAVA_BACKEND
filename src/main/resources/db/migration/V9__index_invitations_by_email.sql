-- =====================================================================
-- V9: Erasure reaches organisation invitations (Phase 4, step 4.5)
--
-- Erasing an account deletes every invitation ever sent to its address: the rows hold the email,
-- which is personal data. That delete filters on email alone, across all organisations, so it
-- needs its own index; without one it scans every invitation of every seller.
-- =====================================================================

CREATE INDEX idx_organization_invitations_email ON organization_invitations (email);
