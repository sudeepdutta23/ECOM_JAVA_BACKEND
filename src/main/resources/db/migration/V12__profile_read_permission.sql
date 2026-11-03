-- =====================================================================
-- V12: Support access to personal data (Phase 5, step 5.3)
--
-- profile:read lets platform staff see a customer's profile and address book, for example to
-- sort out a delivery. Separate from user:read (account status, sessions): a role can manage
-- accounts without seeing where people live. Every use is logged with the viewer's ID.
--
-- Design: docs/modules/PROFILE.md.
-- =====================================================================

INSERT INTO permissions (code, description) VALUES
    ('profile:read', 'View any customer''s profile and addresses (support); every access is logged');

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'profile:read' FROM roles r WHERE r.code = 'ADMIN';
