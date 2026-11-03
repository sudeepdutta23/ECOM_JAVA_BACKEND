# User management design (Phase 3)

Status: complete (Phase 3 review: [PHASE-3-REVIEW.md](../reviews/PHASE-3-REVIEW.md)).

## 1. Purpose and placement

Administrators find accounts, look at them, and act on them: suspend, reactivate, unlock, sign out. Users see and end their own sessions. Accounts can be erased on request.

**Where the code lives: in `auth`, not in a new `user` module.** The `User` entity, its status rules and its sessions already belong to auth, which keeps their invariants: a suspended account cannot log in, cannot refresh, and gets no reset link. A separate `user` module would either reach into auth's entity and repository (breaking the boundary) or need auth to expose nearly every operation anyway. Profile data (names, phone, addresses) is a different concern with its own life cycle; it gets its own `profile` module in Phase 5 and refers to users by ID.

```
auth ──> rbac ──> common
 │  user administration calls rbac for: the caller's permissions (escalation rule),
 │  the target's roles (detail view), and requireCanLeave (last-admin rule)
```

## 2. Scope

| In Phase 3 | Deferred |
|---|---|
| Paginated list with filters (status, email prefix) | Full-text and multi-field search, saved filters (Phase 10) |
| Account detail with roles and lockout state | Profile fields (Phase 5) |
| Suspend with a reason, reactivate | History of every status change (Phase 9 audit log; until then the latest change is on the row and in logs) |
| Unlock a locked account | Admin-created accounts and invitations (needs an email-invite flow; revisit with Phase 7 notifications) |
| Admin "sign out everywhere" for a user | Instant access-token revocation (Phase 11, Redis) |
| A user's own sessions: list and end one | Admin view of another user's sessions |
| Erasure on request, by anonymisation | Data export (with Phase 8 files) |

## 3. Account status model

```
                 verify email
PENDING_VERIFICATION ─────────> ACTIVE <──────────────┐
                                 │  │                 │ reactivate (admin)
                 deactivate (own)│  │suspend (admin)  │
                                 v  v                 │
                       DEACTIVATED  SUSPENDED ────────┘
                            └──────────────────────────┘ reactivate (admin)

any status except ERASED ──erase (owner or admin)──> ERASED   (terminal)
```

| Status | Set by | Can sign in | Meaning |
|---|---|---|---|
| `PENDING_VERIFICATION` | registration | no | Email not confirmed |
| `ACTIVE` | verification, reactivation | yes | Normal |
| `DEACTIVATED` | the owner | no | "Close my account." Only an admin can reopen it |
| `SUSPENDED` (V4) | an administrator, with a reason | no | Blocked by staff, for example for fraud or abuse |
| `ERASED` (V5) | the owner, or an administrator on their request | no | Anonymised; no way back. See section 9 |

**Why a new status instead of reusing `DEACTIVATED`?** They answer different questions. Support must know whether the customer chose to leave or was blocked, and only a suspension carries an internal reason. Merging them would lose that the moment the row changes.

Only `ACTIVE` accounts can be suspended. A pending account cannot sign in anyway, and suspending it would leave a verification link that cannot apply.

The latest change is recorded on the row: `status_changed_at`, `status_changed_by`, `status_reason`. The reason is an internal note: never shown to the account owner, never written to logs (it may contain personal details).

## 4. Data model (V4)

| Change | Why |
|---|---|
| `users.status` CHECK allows `SUSPENDED` | New status |
| `users.status_reason VARCHAR(500)` | Internal note; CHECK: required and non-blank when `SUSPENDED` |
| `users.status_changed_at TIMESTAMPTZ` | When the current status was set (null for statuses set before V4) |
| `users.status_changed_by UUID` FK users, `ON DELETE SET NULL` | Who set it; equals the user's own ID for self-deactivation |
| `refresh_tokens` revoke reasons `ACCOUNT_SUSPENDED`, `ADMIN_SIGN_OUT` | Investigations must tell an admin action from a user logout |
| Permissions `user:read`, `user:manage`, granted to `ADMIN` | See section 6 |
| Indexes, see section 7 | Listing and search |

## 5. Endpoints

| Method | Path | Permission | Step |
|---|---|---|---|
| GET | `/api/v1/admin/users?status=&email=&page=&size=` | `user:read` | 3.2 |
| GET | `/api/v1/admin/users/{userId}` | `user:read` | 3.2 |
| POST | `/api/v1/admin/users/{userId}/suspend` `{reason}` | `user:manage` | 3.3 |
| POST | `/api/v1/admin/users/{userId}/reactivate` | `user:manage` | 3.3 |
| POST | `/api/v1/admin/users/{userId}/unlock` | `user:manage` | 3.3 |
| POST | `/api/v1/admin/users/{userId}/sign-out` | `user:manage` | 3.3 |
| GET | `/api/v1/auth/me/sessions` | own | 3.4 |
| DELETE | `/api/v1/auth/me/sessions/{sessionId}` | own | 3.4 |
| POST | `/api/v1/auth/me/erase` `{password}` | own | 3.5 |
| POST | `/api/v1/admin/users/{userId}/erase` | `user:erase` | 3.5 |

**Why action endpoints (`POST .../suspend`) and not `PATCH {status}`?** Each transition has its own rules, inputs (a reason) and side effects (sessions ended). A generic status field would push all of that behind one ambiguous call and make "set status to ACTIVE" mean four different things.

## 6. Rules

| # | Rule | Error |
|---|---|---|
| 1 | You cannot suspend, reactivate, unlock, sign out or erase yourself through the admin API (use your own account endpoints) | 403 `USER_SELF_ACTION_DENIED` |
| 2 | You may only act on an account whose permissions you all hold. A support agent with `user:manage` cannot suspend an administrator | 403 `RBAC_ESCALATION_DENIED` |
| 3 | The last active administrator cannot be suspended, deactivated or erased | 409 `RBAC_LAST_ADMIN` |
| 4 | A transition must be valid from the current status (for example, only `ACTIVE` can be suspended) | 409 `USER_INVALID_STATUS` |
| 5 | Suspension and admin sign-out end every session of the user | |

Rule 2 is the same escalation rule as Phase 2, applied to people instead of roles. Without it, `user:manage` would be a way to lock out everyone above you.

Access tokens already issued stay valid for up to 15 minutes after a suspension (ADR-003, ADR-010). The refresh is refused at once, so the window is bounded.

**Why refresh re-checks the status even though suspension revokes every token.** Until the Phase 3 review, a refresh running while the suspension committed could insert a successor token the revocation did not see; the status check refused it at its first use. Refresh now locks the account row before the token, so the suspension waits for it and revokes the successor too. The status check stays as defence in depth.

Rule 2 is enforced by `AuthorizationService.requireCanManage` in rbac (the same `EscalationGuard` as for roles); rule 3 by `RoleAssignmentService.requireCanLeave`, under the `ADMIN` row lock. Each action returns the updated account detail.

## 7. Listing and search

Pagination, filters and sorting follow [ADR-011](../adr/ADR-011-pagination.md):

- Offset pagination: `page` (from 0) and `size` (default 20, maximum 100). The response carries `page`, `size`, `totalElements`, `totalPages`.
- Fixed order: newest first, by `id DESC`. IDs are UUID v7, so ID order is creation order, and the primary key index already provides it. No `created_at` index is needed.
- `email` filter is a case-insensitive **prefix** match (`ali` finds `alice@example.com`). `%` and `_` in the input are escaped, so they match literally instead of acting as wildcards.
- `status` filter is exact.
- The email filter is normalized like stored emails (trimmed, lower-case) instead of comparing with `lower(email)`, which would stop the index from being used.
- Filters are added to the SQL only when present (`UserSearch`), so each combination gets its own plan.
- No `Pageable` binding: clients cannot choose the sort column or exceed the page cap. `page` is capped at 10 000.
- Past the last page the result is an empty page, not an error.

| Index (V4) | Serves |
|---|---|
| `idx_users_status_id (status, id)` | Filter by status, newest first, without sorting. Most valuable for rare statuses (`SUSPENDED`, `PENDING_VERIFICATION`) |
| `uk_users_email (email varchar_pattern_ops)`, rebuilt | Equality (login, uniqueness) and prefix search. A default-collation index cannot serve `LIKE 'abc%'` unless the collation is `C`; rebuilding the one index avoids a second index on the same column |
| `idx_users_status_changed_by` | Avoids a full scan when a user is deleted (`ON DELETE SET NULL`) |

**Why prefix and not "contains"?** Admins search by email they already know the start of. A contains search (`%ali%`) needs a trigram index (`pg_trgm`), which is larger and slower to write. Phase 10 revisits search as a whole.

## 8. Sessions (3.4)

A session is one refresh-token family: one sign-in on one device. The list shows the family ID, when it started, when it was last refreshed, and the user agent and IP of the last refresh. Ending a session revokes its family; the device is signed out at its next refresh.

- **`sid` claim.** Access tokens carry the session ID (`sid`, as in OpenID Connect), so the list can mark `current` without reading the refresh cookie. Tokens issued before this change have no `sid`; nothing is marked current for them.
- **404, not 403, for someone else's session.** A 403 would confirm that the ID exists.
- **One query.** Rotation leaves one live token per family; a grouped subquery adds the sign-in time. Both read `idx_refresh_tokens_user_id`.

## 9. Erasure (3.5)

Privacy law (GDPR Art. 17 and similar) gives people the right to have their personal data deleted. Rows that later phases attach to a user (orders, invoices, audit entries) need a valid reference, and some must be kept for years by law. So erasure **anonymises** the account instead of deleting the row.

| Data | After erasure |
|---|---|
| `users.id`, `created_at`, `updated_at`, `password_changed_at` | Kept: not personal on their own, needed for references and statistics |
| `email` | `erased-<id>@erased.invalid`: unique, already normalized, undeliverable (RFC 2606). The real address can register again |
| `password_hash` | `{erased}`: matches no encoder. Login never checks it (below) |
| `email_verified_at`, `last_login_at`, `locked_until`, failed-login count | Cleared |
| `status_reason` | Cleared: an internal note can itself contain personal details |
| `status`, `status_changed_at`, `status_changed_by` | `ERASED`, when, and who handled it (the owner's own ID, or the administrator) |
| Refresh tokens | **Deleted**, not revoked: each row holds an IP address and a user agent |
| Email links (verification, reset) | Deleted |
| Role assignments, `CUSTOMER` included | Deleted. An erased account can never hold a role again: rbac sees it as not existing (404) |

**Two ways in, one implementation.** `POST /me/erase` needs the current password, like deactivation. `POST /admin/users/{id}/erase` is for requests that reached support, possibly from someone who can no longer sign in; it works in any status except `ERASED`. Both call `AccountEraser`, which does everything in one transaction: there is never a half-erased account.

**A separate permission, `user:erase`.** Suspension can be undone, erasure cannot. A support team can hold `user:manage` without being able to destroy accounts. The escalation rule still applies: you cannot erase an account that holds permissions you lack.

**Not idempotent on purpose.** A second erasure returns 409 `USER_INVALID_STATUS`, so a mistyped ID is never reported back as "done".

**The database enforces it.** `ck_users_erased_is_anonymous` (V5) rejects an `ERASED` row that still has a real email, a real hash or any of the cleared fields. A bug, or a manual `UPDATE ... SET status = 'ERASED'`, fails loudly instead of leaving data behind.

**Placeholders cannot be claimed.** Registration refuses every `.invalid` address, and `ck_users_erased_domain_reserved` (V6) allows `@erased.invalid` only on erased rows. Otherwise anyone who knew your user ID could register your future placeholder and make your erasure fail on the unique index.

**A refresh in progress cannot leave a token behind.** Refresh takes a share lock on the account row before it locks the token (see AUTH.md, refresh). Erasure updates that row first, so it waits for the refresh to commit and then deletes the new token too.

**Login.** Login checks the password before the status, so that the response does not reveal the status to someone without the password. For an erased account that would hand `{erased}` to `DelegatingPasswordEncoder`, which throws for an unknown prefix. So login skips erased accounts at lookup: the placeholder email gets the same 401, after the same dummy hash, as an unknown email.

**Access tokens** issued before erasure remain valid for up to 15 minutes, as after any sign-out, but `/me` returns 401 for an erased account, and every write path requires an active account.

**Confirmation email.** The old address travels only in the `AccountErased` event, so that one confirmation can be sent after commit. After that the address exists nowhere in the system. Logs carry only the user ID.

**Organisations (Phase 4).** The only owner of an open organisation cannot be erased or deactivated (409 `ORG_LAST_OWNER`): hand over or close first. Erasure deletes every membership and every invitation sent to the address (`org.service.MemberDeparture`, same transaction).

**Profiles (Phase 5).** Erasure deletes the profile and the address book (`profile.service.ProfileEraser`, same transaction). A profile save locks the account row first, so a save running at the same moment either finishes before the erasure deletes its data, or sees the erased account and is refused.

**Not covered yet.** Files (Phase 8) and audit entries (Phase 9) belong to their modules; each will react to `AccountErased` (Phase 13 events). Database backups still hold the old data until they expire; the retention period of backups is part of the privacy policy, not of this code.

## 10. Plan

| Step | Content |
|---|---|
| 3.1 | This design, ADR-011, V4, `SUSPENDED` status, new permissions |
| 3.2 | List, search and detail endpoints |
| 3.3 | Suspend, reactivate, unlock, sign out |
| 3.4 | Own sessions |
| 3.5 | Erasure |
| 3.6 | Review, journey test, docs |
