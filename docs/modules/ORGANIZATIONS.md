# Organisations design (Phase 4)

Status: complete (Phase 4 review: [PHASE-4-REVIEW.md](../reviews/PHASE-4-REVIEW.md)).

## 1. Purpose

The platform is a **marketplace**: many sellers, one storefront, one customer base.

- A **seller** is an organisation. It will own its catalogue, stock and orders (Phase 6), and its data is isolated from every other seller.
- **People work for sellers** as members, with one organisation role each. One person can work for several sellers and also shop as a customer.
- **Customers are global.** They have no organisation and buy from any seller; a cart can hold products of many sellers.
- **Platform staff** (the `ADMIN` role and custom roles from Phase 2) approve, suspend and close sellers. They act through platform permissions, not memberships.

## 2. Two layers of authorization

| | Platform (Phase 2) | Organisation (Phase 4) |
|---|---|---|
| Question | "May this person do X on the marketplace?" | "May this person do X **for this seller**?" |
| Source | Roles and permissions, `rbac` | Membership row, `org` |
| Where it is checked | `@PreAuthorize` on claims in the access token | A guard reading the membership from the database on each request (step 4.4) |
| Examples | approve sellers (`org:manage`), suspend users | edit the shop, invite staff, later: products, orders |

**Why are memberships not in the access token like platform permissions?**

1. **Unbounded.** A person can work for any number of sellers; claims would grow with each.
2. **Stale access to someone else's data.** A removed member would keep seller data for up to 15 minutes. For the platform that delay is accepted (ADR-010); for a seller who just fired an employee, it is not.
3. **The organisation varies per request**, not per session: one token, many shops.

The lookup is one primary-key read (`pk_organization_members`), proven by `QueryPlanIT`. Phase 11 can cache it.

## 3. Placement and dependencies

New module `org`:

```
auth ──> org ──> rbac ──> common
  └───────────────┘
```

- `org` uses `rbac` for platform permission constants and its `UserDirectory` port ("does this user exist, is the account active, who has this email?"), which auth already implements. No second port with the same questions.
- `org` never imports `auth`. Users are referenced by ID; foreign keys keep integrity.
- `auth` calls `org` before an account goes away (step 4.5), like it calls `rbac.requireCanLeave` today.

## 4. Organisation lifecycle

```
                approve (platform)               suspend (platform, reason)
PENDING_APPROVAL ─────────────> ACTIVE ───────────────────────────> SUSPENDED
                                  ^ └─────────── reactivate (platform) ──┘
any status except CLOSED ──close (owner or platform)──> CLOSED (terminal)
```

| Status | Members can | Sells (Phase 6) |
|---|---|---|
| `PENDING_APPROVAL` | Set up the shop, invite staff | no |
| `ACTIVE` | Everything their role allows | yes |
| `SUSPENDED` | Read only | no; the reason is internal, never shown to members or logged |
| `CLOSED` | Nothing; kept for order history | no |

**Why approval?** A marketplace is liable for what it lists. Real platforms verify sellers (identity, tax details) before they can sell; here the platform approves by hand. The verification itself is out of scope.

**Why is CLOSED terminal?** Reopening would bring back a slug, members and settings nobody has looked at in months. A returning seller creates a new organisation.

**Slugs** are the public handle (`/sellers/acme-store`): 3 to 50 lower-case letters, digits and hyphens, unique, and **immutable**, so links and search-engine entries never break. Lower-case only, so `Acme` and `acme` cannot be two sellers.

## 5. Organisation roles

| Permission | OWNER | MANAGER | STAFF |
|---|---|---|---|
| `ORGANIZATION_READ` (settings, member list) | yes | yes | yes |
| `ORGANIZATION_UPDATE` (name, settings) | yes | yes | |
| `MEMBER_MANAGE` (invite, re-role, remove members ranked below you) | yes | yes | |
| `ORGANIZATION_CLOSE` | yes | | |

Phase 6 adds catalogue and order permissions; each role's set is defined in `OrgRole` and tested in `OrgRoleTest`.

**Fixed roles, not custom ones.** Three levels cover small and mid-size sellers, and every decision stays readable in one enum. Custom per-seller roles would need their own tables, an editor, and Phase 2's escalation rules once per seller. Checks only ask `role.grants(permission)`, so custom roles can come later without touching them.

**Rank rule (step 4.3).** A member manages only members ranked below them; only owners manage owners and grant `OWNER`. It is Phase 2's escalation rule in organisation form: a manager cannot promote themselves or remove the owner.

## 6. Rules

| # | Rule | Step |
|---|---|---|
| 1 | Any active account can create organisations, up to a configured limit of owned ones (default 3) | 4.2 |
| 2 | The founder is the first owner, in the same transaction: no organisation exists without an owner | 4.2 |
| 3 | An organisation that is not closed always has at least one owner whose account is active. Enforced under a row lock on the organisation, like the last-admin rule | 4.3, 4.6 |
| 4 | Joining needs the invitee's consent: an emailed invitation they accept. Nobody is made a member without knowing | 4.3 |
| 5 | Outside your organisations, organisation endpoints answer 404, never 403: they do not confirm that a seller's resource exists | 4.4 |
| 6 | The sole owner of an organisation that is not closed cannot deactivate or erase their account. Transfer ownership or close it first | 4.5 |
| 7 | An administrator may still suspend such an owner: suspension is reversible, and stopping fraud must not wait | 4.5 |
| 8 | Erasure removes every membership; invitations to the erased address are deleted | 4.5 |

## 7. Onboarding (4.2)

| Method | Path | Who | Result |
|---|---|---|---|
| POST | `/api/v1/organizations` `{slug, name}` | any active account | 201, Location; caller is `OWNER`; status `PENDING_APPROVAL` |
| GET | `/api/v1/organizations?page=&size=` | signed in | My organisations with my role, newest first (ADR-011 limits) |
| GET | `/api/v1/organizations/{orgId}` | member | With `myRole` and `myPermissions`, so clients can hide controls |
| PUT | `/api/v1/organizations/{orgId}` `{name, version}` | `ORGANIZATION_UPDATE` | Renamed; optimistic locking like roles |

- **Creation order of checks:** active account, slug not reserved (400), owned limit (409 `ORG_LIMIT_REACHED`), slug free (409 `ORG_SLUG_TAKEN`). Organisation and founder membership are written in one transaction.
- **The owned limit holds under parallel requests.** There is no row to lock (the organisation does not exist yet, and the founder's `users` row belongs to auth), so creation takes a **transaction-scoped advisory lock** keyed by the founder's ID. A second request waits, then counts the first. `OrganizationOnboardingIT` sends six at once: exactly three succeed.
- **Slugs** are normalized before validation (`" Acme-Store"` becomes `acme-store`), checked against a small reserved list (`support`, `official`, `admin`, ...: handles that would let a seller pose as the platform), and never reused, even after closing: a closed seller's old links must not lead to someone else.
- **A slug taken in a race** is detected by `uk_organizations_slug` at `saveAndFlush` and still answers 409 `ORG_SLUG_TAKEN`, not a generic integrity error.
- **Access** (`OrganizationGuard`, section 9): not a member, 404 with the same body as an unknown ID; member without the permission, 403.
- **Suspended and closed organisations are read-only**: 409 `ORG_INVALID_STATUS`. The suspension reason is never in the members' view.

## 8. Members and invitations (4.3)

| Method | Path | Needs | Result |
|---|---|---|---|
| POST | `/{orgId}/invitations` `{email, role}` | `MEMBER_MANAGE`, rank | 201; email with a single-use link |
| GET | `/{orgId}/invitations` | `MEMBER_MANAGE` | Open, unexpired invitations, newest first |
| DELETE | `/{orgId}/invitations/{invitationId}` | `MEMBER_MANAGE`, rank | 204; idempotent |
| POST | `/invitations/accept` `{token}` | the invited address, signed in | The organisation with the new role |
| GET | `/{orgId}/members` | any member | Members with email and role, longest-serving first |
| PUT | `/{orgId}/members/{userId}` `{role}` | `MEMBER_MANAGE`, rank | The member with the new role |
| DELETE | `/{orgId}/members/{userId}` | `MEMBER_MANAGE`, rank | 204 |
| POST | `/{orgId}/leave` | any member | 204 |

All paths are under `/api/v1/organizations`.

**Consent.** A member invites an email address; the invitee accepts the emailed link while signed in **with that address**. A forwarded or leaked link is useless to any other account. The invitee may register after being invited. Inviting reveals nothing about whether the address has an account: the response and the email are the same either way.

**The link.** 256 random bits, only the SHA-256 hash stored, like refresh and account tokens (`OpaqueTokens` moved to `common.security` for this). Valid 7 days, single use, row-locked at acceptance so a double click joins once. Every failure about the link (unknown, used, revoked, expired, other address) is the same 400 `ORG_INVALID_INVITATION`.

**One open invitation per address** (`uk_organization_invitations_open`, a partial unique index). Inviting again revokes the old link and issues a new one, so only the newest works. At most 50 open invitations per organisation (`app.org.max-pending-invitations`): each one sent an email, and the rate limiter only matches fixed paths, so this cap bounds the mail one seller can trigger.

**Rank rule.** `OrgRole.canManage`: owners manage everyone, including owners; others only roles strictly below their own. It applies to the role you invite, and to a member's current AND new role. So a manager can invite and manage staff, but cannot create a manager, promote themselves, or touch an owner.

**Your own membership** cannot be changed or removed through these endpoints (403 `ORG_SELF_ACTION_DENIED`); you leave instead. Leaving works in every status, a suspended organisation included: nobody is kept against their will.

**Last owner.** An organisation that is not closed always keeps an owner who can sign in (409 `ORG_LAST_OWNER`). Owners whose accounts are deactivated or suspended do not count (Phase 4 review): demoting, removing, or the leaving of the last owner is refused. Every membership change locks the organisation row first (`SELECT ... FOR UPDATE`), then checks the caller's role and counts owners. Two owners demoting each other at once are serialised, and exactly one succeeds (`OrganizationMembersIT`).

**Emails of colleagues** come from auth through `UserDirectory.emailsOf`, one query per page. Members of an organisation see each other's addresses; outsiders see nothing.

**Accepted risks.** Two parallel invitations of the same new address can both pass the "already open?" check; the partial unique index stops the second (409). The pending-invitation cap can be exceeded by the number of parallel requests arriving at the limit; it is an anti-spam bound, not a business invariant.

## 9. Tenant isolation (4.4)

Every seller-owned table (Phase 6: products, stock, orders) carries `organization_id NOT NULL` with a foreign key. Four layers keep one seller's data away from every other seller:

1. **URLs name the organisation**: `/api/v1/organizations/{orgId}/products/{productId}`.
2. **`OrganizationGuard.require(orgId, userId, permission, intent)`** at the start of every service method that takes an organisation ID. One query (membership joined to the organisation's status, both by primary key). In order: not a member, 404 (same body as an unknown ID); role lacks the permission, 403; intent `WRITE` while suspended or closed, 409 `ORG_INVALID_STATUS`. Intent `READ` works in every status, so a closed seller's history stays readable. Returns an `OrgMembership` value.
3. **Every repository lookup takes the organisation ID** (`findByIdAndOrganizationId`), so a product ID of another seller is "not found" even inside a correctly guarded call.
4. **Tests that cover new endpoints by themselves:**
   - `TenantIsolationIT` reads every `{orgId}` operation from the live OpenAPI spec and calls each one as an outsider, with a valid body built from the schema examples: every answer must be the same 404 as for a nonexistent organisation, and a snapshot of the target seller must be unchanged. It then calls each operation with a child ID (`{userId}`, `{invitationId}`, later `{productId}`) as the owner of ANOTHER seller, through that seller's own path: 404 again (layer 3). Every path variable needs a fixture, a real ID of the target seller; a new variable fails the test until one is added, so it can never pass on random IDs. Removing the guard from one endpoint makes it fail (checked when it was written).
   - `ModuleBoundariesTest` (ArchUnit) keeps other modules away from org's entities and repositories, so the guard cannot be bypassed from outside.

**What other modules may use.** The org module's public API is its root package (`OrgPermission`, `OrgRole`, `OrganizationStatus`, `OrgMembership`) and its services (`OrganizationGuard`). The JPA entities stay in `org.entity`, private to the module.

**How to add a seller endpoint (Phase 6 checklist).**

1. Path under `/api/v1/organizations/{orgId}/...`.
2. Add the needed `OrgPermission` and grant it in `OrgRole` (`OrgRoleTest` documents the matrix).
3. First line of the service method: `guard.require(orgId, userId, permission, Intent.READ or WRITE)`.
4. Repository finders take `organizationId`; never `findById` on seller data.
5. A new path variable gets a fixture in `TenantIsolationIT`.

See [ADR-012](../adr/ADR-012-tenancy-model.md) for the alternatives (row-level security, Hibernate tenant filters, schema per tenant) and why they were not chosen.

## 10. Platform administration and account departures (4.5)

| Method | Path | Needs | Result |
|---|---|---|---|
| GET | `/api/v1/admin/organizations?status=&page=&size=` | `org:read` | Newest first; `status=PENDING_APPROVAL` is the approval queue |
| GET | `/api/v1/admin/organizations/{organizationId}` | `org:read` | With the internal status reason, actor, member and owner counts |
| POST | `.../{organizationId}/approve` | `org:manage` | `PENDING_APPROVAL` only |
| POST | `.../{organizationId}/suspend` `{reason}` | `org:manage` | `ACTIVE` only; members keep read access |
| POST | `.../{organizationId}/reactivate` | `org:manage` | `SUSPENDED` only |
| POST | `.../{organizationId}/close` | `org:manage` | Any status except `CLOSED` |
| POST | `/api/v1/organizations/{orgId}/close` | owner (`ORGANIZATION_CLOSE`) | Pending or active only |

- **Conflict of interest.** Platform staff cannot act on an organisation they are a member of (403 `ORG_CONFLICT_OF_INTEREST`); otherwise an administrator could approve their own shop or lift its suspension. Reading is allowed.
- **Closing** revokes open invitation links, keeps all data and the slug, and is terminal. An owner cannot close a suspended organisation: closing must not become a way to walk away from a review; the platform can.
- **Every status change locks the organisation row**, like membership changes, so the two never interleave. The suspension reason is shown to staff only, never to members, never logged.
- **The admin path variable is `{organizationId}`**, not `{orgId}`: these endpoints are authorised by platform permission, not membership, so `TenantIsolationIT` (which covers `{orgId}`) does not apply to them.

**Account departures** (rules 6 to 8). auth calls `org.service.MemberDeparture`, inside its own transaction:

| Account change | Organisation rule |
|---|---|
| Deactivation by the owner | Refused (409 `ORG_LAST_OWNER`, naming the slugs) while no other owner with an active account remains in an organisation that is not closed. Memberships stay: deactivation is reversible |
| Erasure, by the owner or an administrator | Same refusal. Then every membership and every invitation ever sent to the address are deleted (V9 indexes `email` for that). Organisations keep the account's ID in `created_by`, `added_by`, `invited_by`: it now points to an anonymous row |
| Suspension by an administrator | No check: reversible, and stopping fraud must not wait for a hand-over |

`requireNotSoleOwner` locks the open organisations the account owns, in ID order: a co-owner cannot leave in the meantime, and two departures cannot deadlock on each other's organisations. In auth, the order is: ADMIN role lock, organisation locks, then the account row.

## 11. Data model (V7, V8, V9)

| Table | Key columns | Notes |
|---|---|---|
| `organizations` | `id`, `slug` (unique), `name`, `status`, `status_reason`, `status_changed_at/by`, `created_by`, `version` | CHECKs: slug format, trimmed name, known status, suspension needs a reason. User FKs `ON DELETE SET NULL` |
| `organization_members` | PK (`organization_id`, `user_id`), `role`, `joined_at`, `added_by` | CASCADE with the organisation and with the user; `added_by` SET NULL; CHECK on role |
| `permissions` | `org:read`, `org:manage` | Granted to `ADMIN` |

| Index | Serves |
|---|---|
| `uk_organizations_slug` | Storefront lookup by slug; uniqueness |
| `idx_organizations_status_id (status, id)` | Approval queue and admin list, newest first, no sort step |
| `pk_organization_members` | Membership check per request; member list; owner count (last-owner rule) |
| `idx_organization_members_user_id` | "My organisations"; owned-organisation limit |
| `idx_organizations_created_by`, `idx_organizations_status_changed_by`, `idx_organization_members_added_by` | `ON DELETE SET NULL` without full scans |

**V8** adds `organization_invitations`: `organization_id` (CASCADE), normalized `email`, `role`, unique `token_hash` (hex CHECK), `invited_by` (SET NULL), `created_at`, `expires_at`, `accepted_at`, `revoked_at`, and a CHECK that an invitation is accepted or revoked, never both. Indexes: `uk_organization_invitations_open (organization_id, email) WHERE accepted_at IS NULL AND revoked_at IS NULL` (one open invitation per address; also serves the pending list) and `idx_organization_invitations_invited_by`.

**V9** adds `idx_organization_invitations_email`: erasure deletes every invitation to an address, across all organisations.

## 12. Plan

| Step | Content |
|---|---|
| 4.1 | This design, ADR-012, V7, entities, organisation roles |
| 4.2 | Seller onboarding: create, my organisations, view, rename; owned-organisation limit |
| 4.3 | Invitations (V8), accept, change role, remove, leave; rank and last-owner rules |
| 4.4 | Organisation guard for seller endpoints; isolation tests |
| 4.5 | Platform administration: approval queue, approve, suspend, reactivate, close; account deactivation and erasure rules |
| 4.6 | Review, journey test, docs |
