# RBAC module design (Phase 2)

Status: complete (Phase 2). Review: [reviews/PHASE-2-REVIEW.md](../reviews/PHASE-2-REVIEW.md).

## 1. Vocabulary

| Term | Meaning | Example |
|---|---|---|
| Permission | One action on one kind of resource. Defined in code. | `role:read`, later `order:refund` |
| Role | A named bundle of permissions. Data; admins can create more. | `ADMIN`, `CUSTOMER`, `ORDER_MANAGER` |
| Assignment | A user holding a role. A user can hold several. | alice → `CUSTOMER`, `ORDER_MANAGER` |
| Authority | What Spring Security checks at runtime | `role:read`, `ROLE_ADMIN` |

**Code checks permissions, never roles.** `@PreAuthorize("hasAuthority('order:refund')")` keeps working when an admin creates a new role that includes refunds. Checking `hasRole('ADMIN')` would need a code change for every new role.

## 2. Scope

| In Phase 2 | Deferred |
|---|---|
| Permission catalogue, system roles, custom roles | Per-organisation roles (Phase 4 adds `organization_id` to `user_roles`) |
| Assign and revoke roles | Resource ownership rules ("only your own orders"), in each business module |
| Roles and permissions in the access token | Attribute-based policies |
| Method security with `@PreAuthorize` | Audit trail of grants (Phase 9; `assigned_by` covers accountability now) |
| Bootstrap of the first administrator | |

## 3. Data model (V3)

```
users 1 ──< user_roles >── 1 roles 1 ──< role_permissions >── 1 permissions
                 │
                 └── assigned_by ──> users (nullable, ON DELETE SET NULL)
```

| Table | Key | Notes |
|---|---|---|
| `permissions` | `code` (natural) | `resource:action`, lower case; seeded by migrations only |
| `roles` | `id` UUID; `code` unique | `code` immutable upper snake case; `name` editable; `system_role` protected |
| `role_permissions` | (`role_id`, `permission_code`) | Cascade with role; RESTRICT on permission |
| `user_roles` | (`user_id`, `role_id`) | Cascade with user; RESTRICT on role; `assigned_by` for accountability |

**Natural vs surrogate keys.** A permission code is already a stable, meaningful identifier that the code depends on, so it is the key. Roles are created at runtime and renamed, so they get a UUID.

**Why a permissions table if permissions live in code?** Foreign keys stop a role from granting a misspelt or removed permission, and admin screens can list what exists. A startup check compares the `Permission` enum with this table and refuses to start if they differ.

### Seed (V3)

| Role | System | Permissions |
|---|---|---|
| `CUSTOMER` | yes | none yet; default for every new account |
| `ADMIN` | yes | all |

Only the three permissions Phase 2 needs exist: `role:read`, `role:write`, `role:assign`. Each later phase adds its own permissions in its own migration, and grants them to `ADMIN`: V4 added `user:read` and `user:manage`, V5 `user:erase` (Phase 3), V7 `org:read` and `org:manage` (Phase 4), V12 `profile:read` (Phase 5).

V3 also **backfills**: every existing user receives `CUSTOMER`. A test replays the upgrade from V2 to prove it.

## 4. Authorities in the access token

```json
{ "sub": "0192...", "roles": ["CUSTOMER", "ORDER_MANAGER"],
  "perms": ["order:read", "order:refund"], "iat": ..., "exp": ... }
```

- Permissions are resolved at login and refresh, and embedded in the JWT. Each request is then authorised from the token alone: no database call, consistent with ADR-003.
- Spring Security authorities become: every `perms` entry as-is, and every role as `ROLE_<code>`.
- **Trade-off:** a change to someone's roles takes effect at their next refresh, at most 15 minutes later. For an urgent revocation, an admin also runs logout-all for that user, so no new tokens are issued. See ADR-010.

## 5. Endpoints

All under `/api/v1/admin`. All require a bearer token.

| Method | Path | Permission | Purpose |
|---|---|---|---|
| GET | `/permissions` | `role:read` | List the catalogue |
| GET | `/roles` | `role:read` | List roles with their permissions |
| GET | `/roles/{roleId}` | `role:read` | One role |
| POST | `/roles` | `role:write` | Create a custom role |
| PUT | `/roles/{roleId}` | `role:write` | Update name, description and permission set |
| DELETE | `/roles/{roleId}` | `role:write` | Delete an unassigned custom role |
| GET | `/users/{userId}/roles` | `role:read` | Roles of a user |
| PUT | `/users/{userId}/roles/{roleId}` | `role:assign` | Grant (idempotent) |
| DELETE | `/users/{userId}/roles/{roleId}` | `role:assign` | Revoke (idempotent) |

`GET /api/v1/auth/me` additionally returns the caller's `roles` and `permissions`, so a frontend can show or hide features. The server still enforces every check.

**Why PUT for granting?** "User X holds role Y" is a resource identified by both IDs. PUT is idempotent: granting twice has the same result as granting once. DELETE likewise.

## 6. Rules that prevent privilege escalation

| # | Rule | Error |
|---|---|---|
| 1 | You can only grant a role whose permissions you hold yourself | 403 `RBAC_ESCALATION_DENIED` |
| 2 | You can only put permissions you hold into a role you create or edit | 403 `RBAC_ESCALATION_DENIED` |
| 3 | You cannot change your own roles | 403 `RBAC_SELF_MODIFICATION_DENIED` |
| 4 | The last active holder of `ADMIN` cannot lose it, by revocation or by deactivating their account | 409 `RBAC_LAST_ADMIN` |
| 5 | System roles are read-only through the API (no edit, no delete); `CUSTOMER` cannot be revoked from a user | 409 `RBAC_SYSTEM_ROLE` |
| 6 | A role still assigned to users cannot be deleted | 409 `RBAC_ROLE_IN_USE` |

Without rules 1 and 2, anyone holding `role:write` could create a role with every permission and, with `role:assign`, give it to themselves.

Rule 2 covers the role's permissions both before AND after an edit: you cannot even strip a role that is stronger than you. The check uses the caller's permissions from the database, not from their token, so a just-demoted admin cannot use a still-valid token to grant powers.

System roles are read-only because the application depends on them; removing a permission from `ADMIN` through the API could lock out every administrator. They change only through migrations.

## 7. Bootstrapping the first administrator

There is no admin until someone grants `ADMIN`, and granting needs an admin. The way out:

- Setting `app.rbac.bootstrap-admin-email` (environment variable `APP_RBAC_BOOTSTRAP_ADMIN_EMAIL`) makes the application, at startup, grant `ADMIN` to that account if it exists, is active, and no active admin exists yet.
- It is idempotent and logged. It does nothing once an admin exists, so leaving the variable set is harmless.
- Register and verify the account first, set the variable, restart.
- An unknown or unverified account is logged as a warning, not a startup failure: refusing to start would block the registration it is waiting for.
- It takes the same `ADMIN` row lock as revocation, so several instances starting at once grant at most once.

## 7a. How rbac learns about users

Assignment needs "does this user exist?" and the last-admin rule needs "which admins can still sign in?". rbac declares these questions in the `UserDirectory` interface; auth implements it (`AuthUserDirectory`). The code dependency stays `auth -> rbac` (dependency inversion), and rbac never reads the `users` table itself.

The opposite direction is allowed: auth calls rbac. Account deactivation calls `RoleAssignmentService.requireCanLeave` inside its transaction, so the last active administrator cannot deactivate (409 `RBAC_LAST_ADMIN`). Phase 3's admin suspension will call the same method.

## 8. Transactions and concurrency

| Operation | Transaction | Concurrency |
|---|---|---|
| Grant / revoke | One write tx | Revoking `ADMIN` locks the `ADMIN` role row (`SELECT ... FOR UPDATE`) before counting admins, so two admins cannot demote each other at the same time and leave none |
| Update role permissions | One write tx | `@Version` on `roles`: concurrent edits fail with 409 instead of one silently overwriting the other |
| Login / refresh authority lookup | Read-only, one query | Joins `user_roles`, `roles`, `role_permissions` by user ID, all indexed |
