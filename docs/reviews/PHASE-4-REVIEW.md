# Phase 4 review: Organisations (marketplace sellers)

- Date: 2026-11-02
- Scope: the `org` module (onboarding, members, invitations, guard, platform administration), the auth changes that call it (deactivation, erasure), migrations V7 to V9, and the architecture rules introduced with it
- Result: complete; all automated tests passing (413 unit, 272 integration; see [TESTING.md](../TESTING.md))

## 1. What was delivered

| Capability | Endpoint(s) | Authorised by |
|---|---|---|
| Seller onboarding, owned-organisation limit | `POST /organizations` | any active account |
| My organisations, view, rename | `GET /organizations`, `GET, PUT /organizations/{orgId}` | membership and organisation role |
| Invitations with consent | `POST, GET /{orgId}/invitations`, `DELETE /{orgId}/invitations/{id}`, `POST /invitations/accept` | `MEMBER_MANAGE` and rank; the invited address |
| Members | `GET /{orgId}/members`, `PUT, DELETE /{orgId}/members/{userId}`, `POST /{orgId}/leave` | organisation role and rank |
| Closing by an owner | `POST /{orgId}/close` | `ORGANIZATION_CLOSE` |
| Platform administration | `GET /admin/organizations[/{id}]`, `POST .../approve, suspend, reactivate, close` | `org:read`, `org:manage` |
| Guard for later seller modules | `OrganizationGuard.require(orgId, userId, permission, intent)` | |
| Account departures | `MemberDeparture`, called by auth on deactivation and erasure | |

Design: [modules/ORGANIZATIONS.md](../modules/ORGANIZATIONS.md) and [ADR-012](../adr/ADR-012-tenancy-model.md).

## 2. Security review

Method: for each capability, list who could misuse it (an outsider who knows an ID, a staff member, a manager, another seller's owner, a platform administrator with their own shop) and what must still hold afterwards (a seller's data stays its own, every open seller has someone who can manage it, nobody joins without consent, an erased person leaves nothing behind). Each answer points to a rule and the test that proves it.

### Findings fixed during the review

| # | Finding | Severity | Fix | Proof |
|---|---|---|---|---|
| 1 | The last-owner rule counted memberships, not people who can sign in. With two owners, one could deactivate their account (allowed: the other is active), after which the remaining owner could leave: the shop was left with a single, deactivated owner and nobody able to manage it. The same through a suspended co-owner, and in account departures | Medium (an open seller nobody can manage; only the platform could intervene) | Both `MembershipService` and `MemberDeparture` now count the OTHER owners whose accounts are active (`findOwnerIds` plus `UserDirectory.countActive`), like the platform's last-admin rule | `MembershipServiceTest.anOwnerWhoLeavesNoOtherActiveOwnerIsRefusedEvenIfInactiveOwnersRemain`, `MemberDepartureTest`, `OrganizationMembersIT.theLastActiveOwnerCannotLeaveAShopWhoseOtherOwnerIsDeactivated`, `OrganizationDepartureIT.aSuspendedCoOwnerDoesNotCountAsSomeoneWhoCanTakeOver` |

Earlier steps already fixed what their own tests found: `PermissionController` reading its repository directly (found by `ModuleBoundariesTest` in 4.4), and the plan test depending on test order (4.1).

### Threat checklist

| Attack or failure | Blocked by | Evidence |
|---|---|---|
| An outsider reads or changes a seller's data by ID | Guard: non-members get the same 404 as for an unknown organisation | `TenantIsolationIT` (every `{orgId}` operation, discovered from OpenAPI); proven to fail when a guard is removed |
| Another seller's owner uses their own `{orgId}` with this seller's child IDs | Every lookup scoped by organisation (`findByIdAndOrganizationId`, member keys) | `TenantIsolationIT.anotherSellersOwnerCannotReachTheTargetsDataThroughTheirOwnOrganization` |
| Another module reads org tables directly, around the guard | Entities and repositories are private to the module | `ModuleBoundariesTest` |
| A removed member keeps access | Memberships read from the database per request, not from the token | `OrganizationMembersIT`; ADR-012 |
| A forwarded or leaked invitation link is used by someone else | The accepting account's email must match; same 400 for every bad link | `InvitationServiceTest.aForwardedLinkDoesNotWorkForAnotherAccount`, `OrganizationMembersIT` |
| A link is accepted twice by a double click | Row lock at acceptance, single use | `InvitationServiceTest`, `OrganizationMembersIT` (second accept 400) |
| A manager promotes themselves, creates managers or removes an owner | Rank rule on the role invited, the current and the new role; self-changes refused | `OrgRoleTest`, `MembershipServiceTest`, `OrganizationMembersIT` |
| Two owners demote each other to zero owners | Organisation row lock before every membership change | `OrganizationMembersIT.twoOwnersDemotingEachOtherAtOnceLeaveExactlyOneOwner` |
| An open seller loses its last owner through account deactivation or erasure | `MemberDeparture.requireNotSoleOwner`, under locks on the owned organisations | `OrganizationDepartureIT` |
| An administrator approves their own shop or lifts its suspension | Conflict-of-interest rule | `OrganizationAdminIT.staffCannotApproveOrRescueTheirOwnShop` |
| A suspended seller closes itself to escape the review | Owner close needs a writable organisation | `OrganizationAdminIT` |
| A seller poses as the platform (`support`, `official`) | Reserved slugs; lower-case, immutable, never reused | `OrganizationOnboardingIT` |
| Spam sellers or invitation mail floods | Owned-organisation limit under an advisory lock; 50 pending invitations per seller | `OrganizationOnboardingIT.parallelCreationsCannotExceedTheOwnedLimit`, `InvitationServiceTest` |
| Inviting reveals whether an email has an account | Same response and email either way | `InvitationService` design; `OrganizationMembersIT` (invitee registers afterwards) |
| An erased person stays in organisations or invitations | `MemberDeparture.forget`: memberships and every invitation to the address | `OrganizationDepartureIT` |
| Suspension reasons, emails or tokens in logs | IDs only; `toString` overrides on entities, events and requests | `SellerJourneyIT` log audit, `OrganizationMembersIT` |

### Accepted risks and recommendations

| Item | Decision |
|---|---|
| The guard is a check, not a lock: a write that started just before a suspension can still commit | Accepted for Phase 4 (membership and status changes do lock the organisation row). Phase 6 sales must take a share lock on the organisation (or re-check status in the order transaction) so nothing is sold after a suspension |
| Members see each other's email addresses | Accepted: colleagues need to know who they work with. Outsiders see nothing |
| Invitation links carry the token in the URL query (frontend page), so it can appear in browser history | Same as email verification links: single use, 7 days, hashed at rest; the API takes it in the body |
| Two parallel invitations of the same new address: the second gets a generic 409 from the unique index | Rare; no data problem |
| The pending-invitation cap can be exceeded by requests arriving in parallel at the limit | Anti-spam bound, not an invariant |
| An administrator can suspend a sole owner, leaving the shop without an active owner until reactivation | By design (rule 7): stopping fraud must not wait; the platform can reactivate or close the shop |
| No emails to sellers on approval or suspension | Phase 7 (notifications) |
| No row-level security in the database | Recommended as a second line of defence in Phase 18 (ADR-012) |

## 3. Performance review

| Path | Work | Cost |
|---|---|---|
| Every seller request | `OrganizationGuard`: one query joining the membership to the organisation's status | Two primary-key lookups; `QueryPlanIT`. Cacheable in Phase 11 |
| Membership changes | Organisation row lock, the caller's membership, owner IDs, one `countActive` | All by primary key; contention only within one seller |
| Organisation creation | Advisory lock per founder, owned count via `idx_organization_members_user_id` | Contention only between one user's own parallel requests |
| Member list | One page query, one email lookup for the page | No N+1 |
| Account erasure | Locks on owned open organisations, two bulk deletes | `idx_organization_members_user_id`, `idx_organization_invitations_email` (V9) |

Conclusions:

- **Every new query uses an index.** `QueryPlanIT` checks 42 plans over about 5 000 synthetic accounts and 2 000 sellers with invitations, so the planner's choice reflects real selectivity instead of test order. Where two indexes are equally selective, the test accepts either.
- **No N+1.** Lists are one page query plus at most one batch lookup.
- **Locks are per seller** (organisation row) or per founder (advisory), never global.

## 4. Production quality checklist

| Question | Answer |
|---|---|
| Loosely coupled? | Yes. `auth -> org -> rbac -> common`, enforced by `ModuleBoundariesTest`; org's public API is its root package and services; entities stay private |
| Authorization secure? | Yes, see section 2; one finding fixed with proof; isolation tested for every organisation endpoint automatically |
| Indexes and constraints correct? | Yes; V7 to V9 tested in `OrganizationSchemaIT` (slug, status, roles, invitations, cascades, SET NULL) |
| Queries efficient? | Yes, see section 3 |
| Sufficiently tested? | Unit, web slice, schema, HTTP, two real races (owners, owned limit), a mutation check of the isolation test, a full journey with log audit |
| Understandable? | ORGANIZATIONS.md (rules, lifecycle, checklist for Phase 6), ADR-012, Javadoc on each rule, this review |
| Scalable? | Stateless platform authorization; per-request organisation check by primary key; per-seller locks only |
| Observable? | Every organisation, membership and invitation change is logged with IDs and the request ID; no personal data |
| APIs consistent? | Same envelope and Problem Details; stable `ORG_*` codes; command endpoints for transitions; Postman collection checked against OpenAPI |
