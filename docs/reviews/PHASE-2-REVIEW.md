# Phase 2 review: Authorization (RBAC) module

- Date: 2026-11-01
- Scope: everything under `rbac/`, the auth changes that feed it (token claims, `/me`, registration, deactivation) and the shared security configuration
- Result: complete; all automated tests passing (see [TESTING.md](../TESTING.md))

## 1. What was delivered

| Capability | Endpoint(s) |
|---|---|
| Permission catalogue, defined in code and checked against the database at startup | `GET /permissions` |
| Custom roles with optimistic locking | `GET, POST /roles`, `GET, PUT, DELETE /roles/{roleId}` |
| Role assignment | `GET /users/{userId}/roles`, `PUT, DELETE /users/{userId}/roles/{roleId}` |
| Roles and permissions in access tokens, `@PreAuthorize` method security | all of the above |
| Roles and permissions in `/me` | `GET /api/v1/auth/me` |
| First-administrator bootstrap | `APP_RBAC_BOOTSTRAP_ADMIN_EMAIL` |

All admin endpoints are under `/api/v1/admin`. Design: [modules/RBAC.md](../modules/RBAC.md) and [ADR-010](../adr/ADR-010-authorization-model.md).

## 2. Security review

Method: list every way an authenticated user could gain, keep or remove power they should not have, and for each one point to the rule and the test that blocks it.

### Findings fixed during the review

| # | Finding | Severity | Fix | Proof |
|---|---|---|---|---|
| 1 | The last active administrator could deactivate their own account, leaving nobody able to administer the system (only the bootstrap setting could recover it) | Medium (availability of administration) | Deactivation calls `RoleAssignmentService.requireCanLeave`, which takes the same `ADMIN` row lock as revocation and returns 409 `RBAC_LAST_ADMIN` | `RoleAssignmentServiceTest.theLastActiveAdminMayNotLeave`, `AccountServiceTest.theLastActiveAdministratorCannotDeactivate`, `AdminJourneyIT` steps 3 and 7 |

### Threat checklist

| Attack | Blocked by | Evidence |
|---|---|---|
| Create a role with permissions you lack, then get it assigned | Escalation rule on role contents | `RoleManagementIT.editorWithoutRoleAssignCannotCreateARoleThatGrantsIt` |
| Strip a stronger role by editing it | Rule covers permissions before AND after the edit | `RoleManagementIT.editorCannotEditTheAdminLevelRoleItCannotMatch` |
| Hand out a role stronger than yourself | Escalation rule on grant | `RoleAssignmentIT.anAssignerCanOnlyHandOutRolesWithinTheirOwnPower` |
| Demote someone stronger than yourself | Escalation rule on revoke | same test |
| Promote yourself, or quietly demote yourself | Self-modification rule | `RoleAssignmentIT.nobodyChangesTheirOwnRoles`, `AdminJourneyIT` step 3 |
| Use a still-valid token after being demoted, to grant powers | Management actions re-read the caller's permissions from the database | `AdminJourneyIT` step 5 (`RBAC_ESCALATION_DENIED` with the old token) |
| Two admins demote each other at the same moment | `ADMIN` row lock taken before any check | `RoleAssignmentIT.twoAdminsDemotingEachOtherAtOnceLeaveOneAdmin` |
| Lock everyone out by editing `ADMIN` | System roles are read-only through the API | `RoleManagementIT.systemRolesCannotBeChangedOrDeleted` |
| Forge or edit the `perms` claim | RS256 signature, algorithm pinned (Phase 1) | `SecurityIT` |
| Make a permission look like a role authority (`ROLE_...`) | Permission codes are constrained to `^[a-z]+(:[a-z]+)+$` in the database | `RbacSchemaIT` |
| A permission in code that the database does not know (every check silently denies) | Startup refuses to run when catalogues differ | `PermissionCatalogVerifierTest` |
| Check a role name in code, so a new role silently lacks access | Code checks permissions only; `hasAuthority` on constants | `PermissionControllerTest` (bare role names do not pass) |
| Lost update when two admins edit one role | `version` in the request plus `@Version` | `RoleManagementIT.staleVersionCannotOverwriteSomeoneElsesChange` |
| Bootstrap used as a back door later | Acts only while no active admin exists, under the `ADMIN` lock | `RoleAssignmentIT.bootstrapPromotesTheConfiguredAccountOnlyWhileNoAdminExists` |
| Secrets or emails in authorization logs | Logs carry user IDs and role codes only; bootstrap masks the email | `AdminJourneyIT` log audit |

### Accepted risks and recommendations

| Item | Decision |
|---|---|
| A demoted user's access token keeps its claims for up to 15 minutes (endpoints guarded only by `@PreAuthorize` still accept it) | Accepted (ADR-010). Management actions already re-check the database. Option later: a `jti` denylist, or a "permissions changed at" timestamp per user checked by a filter (Phase 11, with Redis) |
| A concurrent grant by an admin who is being demoted at that instant may still succeed | Accepted: the grant is ordered before the demotion, and `assigned_by` records who did it |
| No audit trail of role changes beyond `assigned_by` and logs | Phase 9 adds an audit log |
| Permissions in the token grow with every phase | About 15 bytes per permission; even 100 permissions stay far below header limits. Revisit if a role ever needs hundreds |
| Admin endpoints are not rate limited | Low risk: they need an admin token. Revisit with the gateway in Phase 18 |

## 3. Performance review

No live latency was re-measured in this phase. What changed on the hot paths, and why it is cheap:

| Path | Added work | Cost |
|---|---|---|
| Login, refresh | One indexed join for roles and permissions (`findRolesAndPermissions`) | Uses `pk_user_roles`, `pk_roles`, `pk_role_permissions`; proven by `QueryPlanIT` |
| Every authenticated request | Reading two claims from the already-verified JWT | No database call; authorization is decided from the token |
| `/me` | Same single join as login | One extra indexed query |
| Revoking `ADMIN`, deactivation | One row lock, holder list, one `count(*)` over primary keys | Admin-only, rare |

Conclusions:

- **No N+1 queries.** Token claims come from one native join. Role lists use `join fetch`. A user's roles come from one JPQL join into a record.
- **Every new query uses its index.** `QueryPlanIT` now checks 14 plans, including the `ADMIN` lock (`uk_roles_code`) and the holder lookup (`idx_user_roles_role_id`).
- **Lock scope is minimal.** Only changes to who holds `ADMIN` take a lock, on one row; ordinary grants need none, because `ON CONFLICT DO NOTHING` makes them idempotent.

## 4. Production quality checklist

| Question | Answer |
|---|---|
| Loosely coupled? | Yes. `auth -> rbac -> common`; rbac reaches user data only through the `UserDirectory` interface that auth implements |
| Authorization secure? | Yes, see section 2; one finding fixed with proof |
| Indexes and constraints correct? | Yes; V3 constraints tested in `RbacSchemaIT`, including the upgrade from V2 |
| Queries efficient? | Yes, see section 3 |
| Sufficiently tested? | Unit, web slice, schema, HTTP integration, a real concurrency race, a full journey with log audit |
| Understandable? | Javadoc on each rule; ADR-010; RBAC module doc; this review |
| Scalable? | Authorization is stateless (claims in the token); the only lock is per `ADMIN` change |
| Observable? | Every grant, revoke, role change and blocked escalation is logged with user IDs and the request ID |
| APIs consistent? | Same envelope and Problem Details; stable `RBAC_*` codes; Postman collection checked against OpenAPI |
