# Phase 3 review: User management

- Date: 2026-11-02
- Scope: user administration, own sessions and erasure in `auth/` (`UserAdminService`, `SessionService`, `AccountEraser`, their controllers and repositories), the rbac hooks they use (`requireCanManage`, `requireCanLeave`, `removeAllRoles`), migrations V4 to V6, and the refresh flow they interact with
- Result: complete; all automated tests passing (319 unit, 217 integration; see [TESTING.md](../TESTING.md))

## 1. What was delivered

| Capability | Endpoint(s) | Permission |
|---|---|---|
| Account list with status and email-prefix filters, offset paging | `GET /admin/users` | `user:read` |
| Account detail: status history, lockout, sessions, roles | `GET /admin/users/{userId}` | `user:read` |
| Suspend with an internal reason, reactivate | `POST /admin/users/{userId}/suspend`, `/reactivate` | `user:manage` |
| Unlock, sign out everywhere | `POST /admin/users/{userId}/unlock`, `/sign-out` | `user:manage` |
| Erasure on request | `POST /admin/users/{userId}/erase` | `user:erase` |
| Own sessions: list, end one | `GET /auth/me/sessions`, `DELETE /auth/me/sessions/{sessionId}` | own |
| Own erasure | `POST /auth/me/erase` | own, with password |

Statuses `SUSPENDED` (V4) and `ERASED` (V5); `sid` claim in access tokens. Design: [modules/USERS.md](../modules/USERS.md) and [ADR-011](../adr/ADR-011-pagination.md).

## 2. Security review

Method: for each new capability, ask who could misuse it (a customer, a support agent, an administrator, an outsider who knows an ID) and what the system must still guarantee afterwards (someone can administer, a suspended person cannot get in, an erased person leaves nothing behind). Each answer points to a rule and the test that proves it.

### Findings fixed during the review

| # | Finding | Severity | Fix | Proof |
|---|---|---|---|---|
| 1 | Anyone could register `erased-<id>@erased.invalid` for another person's user ID. That person's later erasure would then fail on `uk_users_email`: a way to block someone's right to erasure. User IDs are rarely visible today, but later phases will show some | Medium (privacy obligation) | Registration refuses every `.invalid` address (RFC 2606: it can never receive mail anyway); V6 `ck_users_erased_domain_reserved` allows `@erased.invalid` only on erased rows | `RegistrationServiceTest.reservedInvalidDomainsAreRejectedBeforeAnyWork`, `AccountErasureIT.nobodyCanClaimSomeoneElsesErasurePlaceholder` |
| 2 | A refresh running while an erasure committed could insert its successor token after the erasure's `DELETE`. The token was unusable, but kept an IP address and a user agent: personal data surviving the erasure. Suspension had the same race (the leftover token was refused at use, so only a revocation was missed) | Medium (incomplete erasure) | Rotation takes a share lock on the owner's `users` row first (`lockOwnerStatusByTokenHash`), then the token lock, and trusts the status read under the lock. Account-wide changes update the `users` row first, so they wait for the refresh and then see its token. Same lock order on both sides: no deadlock | `RefreshTokenServiceTest.theAccountIsLockedBeforeTheToken`, `.theStatusReadUnderTheLockWinsOverAStaleEntity`, `.aTokenDeletedWhileWaitingForTheLockIsUnknown`; `AccountErasureIT.anErasureWaitsForARefreshInProgressAndDeletesItsNewToken` |

Both fixes are new code (V6, a new query), not edits of earlier migrations: V5 may already have run on a developer database.

### Threat checklist

| Attack or failure | Blocked by | Evidence |
|---|---|---|
| A support agent suspends, signs out or erases an administrator | Escalation rule: the caller must hold every permission of the target | `UserAdminActionsIT.aSupportAgentCanManageCustomersButNotAdministrators`, `UserAdministrationJourneyIT` step 5 |
| `user:manage` used to destroy accounts | Erasure needs its own `user:erase` | `AccountErasureIT.erasureNeedsItsOwnPermission`, `UserAdminControllerTest.eraseNeedsUserEraseNotJustManage` |
| An administrator acts on their own account through the admin API | 403 `USER_SELF_ACTION_DENIED` for every action | `UserAdminActionsIT.noActionWorksOnYourOwnAccount`, `UserAdminServiceTest.noActionWorksOnYourOwnAccount` |
| Nobody left to administer (suspend, deactivate or erase the last admin) | `requireCanLeave` under the `ADMIN` row lock | `UserAdminActionsIT.theLastActiveAdministratorCannotBeSuspended`, `AccountErasureIT.theLastActiveAdministratorCannotEraseThemselves` |
| A suspended user keeps working | All sessions revoked; refresh and login re-check the status; password reset refused | `UserAdminActionsIT.suspensionEndsSessionsAndBlocksSignInUntilReactivated` |
| The suspension reason leaks to the owner or to logs | Generic login message; reason never logged | same test; journey log audit |
| Ending someone else's session, or probing which session IDs exist | Ownership check; 404, not 403 | `SessionsIT.someoneElsesSessionIs404AndKeepsWorking` |
| Credentials in the admin list or detail | Explicit allow-list DTOs | `UserAdminIT.listNeverExposesCredentials` |
| Search input as wildcards (`%`, `_`) or as SQL | Escaped LIKE in a Criteria parameter | `UserSearchTest.wildcardsAndTheEscapeCharacterAreEscaped`, `UserAdminIT.wildcardsInTheSearchMatchLiterally` |
| Huge pages or unindexed sorts to load the database | `size` 1 to 100, `page` up to 10 000, fixed order, no `Pageable` binding | `UserAdminControllerTest.sizeAbove100IsAValidationErrorNotSilentlyReduced` |
| An erased account keeps personal data (bug or manual SQL) | V5 `ck_users_erased_is_anonymous` | `AccountErasureIT.theDatabaseRefusesAnErasedRowThatStillHoldsPersonalData` |
| Login with an erased account's placeholder crashes the encoder (500) or reveals the account | Lookup skips erased accounts: same 401 and timing as an unknown email | `AuthenticationServiceTest.anErasedAccountLooksExactlyLikeAnUnknownEmail`, `AccountErasureIT.theOwnerErasesTheirAccountAndNothingPersonalRemains` |
| An erased account is given roles again | rbac sees it as not existing (404) | `AccountErasureIT.anAdministratorErasesASuspendedAccountOnce` |
| A mistyped ID during erasure reported as success | Second erasure is 409, not idempotent | same test |
| Lost update when an admin and the owner change the account at once | `@Version` on `users`; 409 `CONCURRENT_MODIFICATION` | inherited from Phase 1; erasure and suspension go through the entity |
| Emails, tokens, passwords or reasons in logs | User IDs only | `UserAdministrationJourneyIT` log audit, `AccountErasureIT` |

### Accepted risks and recommendations

| Item | Decision |
|---|---|
| Access tokens stay valid up to 15 minutes after suspension, sign-out or erasure | Accepted (ADR-003, ADR-010). `/me` refuses erased accounts, every write needs an active account. Instant revocation with Redis in Phase 11 |
| A password-reset request racing an erasure can send one reset email after the erasure confirmation; the link fails (account not active) and the stale link row holds no personal data | Accepted: rare, harmless; revisit with the outbox in Phase 13 |
| Erasure does not reach backups, or data of later modules (profile, files, audit) | Each module reacts to `AccountErased` when it exists (Phase 13 events). Backup retention belongs in the privacy policy |
| Only the latest status change is stored on the row | Phase 9 audit log keeps the full history |
| `count(*)` for an unfiltered admin list grows with the table | Fine for admin use at this scale; ADR-011 describes keyset paging if it ever matters |
| Admin endpoints are not rate limited | Low risk behind an admin token; revisit with the gateway in Phase 18 |

## 3. Performance review

| Path | Added work | Cost |
|---|---|---|
| Refresh (hot) | One share lock on the owner's row, via the token-hash unique index and the users primary key | One extra indexed statement. Concurrent logins of the same user wait only for the refresh to commit (milliseconds) |
| Login | Skips erased accounts in memory | None |
| Admin list | One page query plus one count, filters only when present | `idx_users_status_id` and `uk_users_email` (pattern ops); no sort step |
| Session list | One query with a grouped subquery | `idx_refresh_tokens_user_id` for both parts |
| Erasure | Three bulk deletes, one update | Each delete uses an index leading with `user_id` |

Conclusions:

- **Every new query uses its index.** `QueryPlanIT` checks 24 plans (21 before this phase's erasure step).
- **The plan test is now deterministic.** With only a few rows, several indexes cost the same and the chosen one depended on test order. `QueryPlanIT` now loads 5 000 synthetic accounts once, analyses, checks, and removes them.
- **No N+1.** Admin detail is three indexed queries (account, roles and permissions in one join, session count) regardless of how many roles or sessions the account has.
- **Lock scope is minimal.** Row locks only: the account row during a refresh, the `ADMIN` role row for last-admin decisions.

## 4. Production quality checklist

| Question | Answer |
|---|---|
| Loosely coupled? | Yes. User management stays in auth, which owns the `User` invariants; rbac is called through three narrow methods and still never imports auth |
| Authorization secure? | Yes, see section 2; two findings fixed with proof |
| Indexes and constraints correct? | Yes; V4 to V6 tested in `UserManagementSchemaIT` and `AccountErasureIT`; database checks back the key rules (suspension reason, anonymity, reserved domain) |
| Queries efficient? | Yes, see section 3 |
| Sufficiently tested? | Unit, web slice, schema, HTTP integration, a lock-ordering race, a full journey with log audit |
| Understandable? | USERS.md explains each rule and decision; Javadoc on each service rule; this review |
| Scalable? | Stateless authorization; per-row locks only; paging capped |
| Observable? | Each admin action and erasure logged with user IDs, the actor and the request ID |
| APIs consistent? | Same envelope, Problem Details, stable `USER_*` codes; command endpoints for transitions; Postman collection checked against OpenAPI |
