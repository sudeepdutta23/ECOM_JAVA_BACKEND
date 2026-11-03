# Phase 5 review: Profile

- Date: 2026-11-03
- Scope: the `profile` module (personal profile, address book, support view), the data export in auth, the `UserDirectory.lockIfActive` port method, migrations V10 to V12
- Result: complete; all automated tests passing (495 unit, 305 integration; see [TESTING.md](../TESTING.md))

## 1. What was delivered

| Capability | Endpoint(s) | Authorised by |
|---|---|---|
| Personal profile: names, phone, language, time zone | `GET, PUT /api/v1/profile` | the account in the token |
| Address book with defaults, limit and country rules | `GET, POST /api/v1/profile/addresses`, `GET, PUT, DELETE /{addressId}` | the account in the token; owner-scoped lookups |
| Support view of a customer's personal data | `GET /api/v1/admin/users/{userId}/profile` | `profile:read`; every view logged |
| Download my data, across every module | `GET /api/v1/auth/me/export` | the account in the token; rate limited |
| Erasure of profile and addresses | `ProfileEraser`, called by auth's `AccountEraser` | |
| Lock against erasure for every personal-data write | `UserDirectory.lockIfActive` | |

Design: [modules/PROFILE.md](../modules/PROFILE.md).

## 2. Security review

Method: for each capability, list who could misuse it (another customer who knows or guesses an ID, a token that outlived its account, a scripted account, a support agent, someone reading logs or a cache) and what must still hold (a person sees and changes only their own data; an erased person leaves no personal data; staff access is deliberate and traceable; nothing personal is logged). Each answer points to a rule and the test that proves it.

### Findings

No defect was found in this review. Two issues were found and fixed while the steps were built, and are listed because the reasoning matters:

| # | Issue | Fix | Proof |
|---|---|---|---|
| 1 | (5.1) A profile save running alongside an erasure could write a fresh profile right after the erasure deleted the old one: the access token outlives the erasure by up to 15 minutes, and "is the account active?" was not enough without a lock | `UserDirectory.lockIfActive` locks the account row FOR SHARE before every write of personal data; erasure's update waits for it, then deletes what was written | `ProfileIT.anErasureWaitsForASaveInProgressAndThenDeletesWhatItWrote` |
| 2 | (5.2) A first draft inserted a new address and then updated its flags, so a fresh address started at version 1, and an edit that moved a default flushed twice (two versions) | Old defaults are cleared before the address is written, one statement per write | `AddressServiceTest` (version 0 on create, clear before the edit) |

### Threat checklist

| Attack or failure | Blocked by | Evidence |
|---|---|---|
| Another customer reads or changes my profile | No account ID in the URL; the account comes from the token; unknown fields (`userId`) rejected | `ProfileControllerTest`, `ProfileIT` |
| Another customer uses my address ID (IDOR) | Owner-scoped finders only (`findByIdAndUserId`); same 404 as an unknown ID for read, replace, delete | `AddressBookIT.anotherPersonsAddressAnswersExactlyLikeAnUnknownOne` |
| An erased or deactivated account keeps writing with a still-valid token | `lockIfActive` before every write | `ProfileIT`, `AddressBookIT`, `ProfileServiceTest`, `AddressServiceTest` |
| A write slips in behind an erasure | FOR SHARE lock on the account row; erasure updates the row first, then deletes | `ProfileIT` (paused save) |
| Two devices overwrite each other | `version` on every write, also on the first profile save; bulk default changes bump the old address's version | `ProfileIT`, `AddressBookIT` |
| Parallel requests pass the 20-address limit or leave two defaults | Advisory lock per address book; partial unique indexes as the last line | `AddressBookIT.parallelRequestsCannotPassTheLimitTogether`, `ProfileSchemaIT` |
| A name that displays as something else (U+202E), hidden control characters | Rejected in every text field | `ProfileControllerTest`, `AddressControllerTest` |
| Account administrators browse where people live | `profile:read`, separate from `user:read` and `user:manage` | `PersonalDataIT.accountAdministrationAloneDoesNotOpenPersonalData`, `ProfileAdminControllerTest` |
| Support access leaves no trace | Every view logged with viewer and request ID | `PersonalDataIT`, `ProfileJourneyIT` |
| The support view reveals which accounts were erased | Same 404 for unknown and erased | `PersonalDataIT.supportGetsTheSame404ForUnknownAndErasedAccounts` |
| The export leaks credential material | Built from public module APIs that never carry hashes; checked for `argon2`, `passwordHash`, `tokenHash` | `PersonalDataIT`, `PasswordAndAccountControllerTest` |
| The export lingers in a proxy or browser cache | `Cache-Control: no-store`, download disposition | `PersonalDataIT` |
| Export used to load the system | 5 per 15 minutes per IP | `application.yml` rate-limit rule `data-export` |
| An unverified phone number used to take over an account | Contact data only: never a sign-in, recovery or second factor | Design rule (PROFILE.md section 3) |
| Personal data in logs | IDs only; `toString` overrides on every entity, record and DTO holding it | `ProfileJourneyIT` log audit, `ProfileIT`, `AddressBookIT`, entity tests |

### Accepted risks and recommendations

| Item | Decision |
|---|---|
| A support agent with `profile:read` can view the profile of a platform administrator | Accepted: support needs every customer, and every view is logged. The escalation rule of Phase 2 protects actions, not reads (like `user:read`). Phase 9 audit entries make the views reviewable |
| The access log is the application log, not a database table | Until Phase 9 (audit). Log retention and access control are an operations concern (Phase 14) |
| The export is synchronous JSON | Fine for today's volume (one page of data). When orders and files join it, move to an asynchronous job that emails a link (Phase 12) |
| Address formats are checked, not verified | A verification service when checkout needs guaranteed deliverability |
| Phone numbers are not verified | SMS verification in a later phase before any use beyond contact |
| Suspension reasons are not in the export | Disclosed on request through support, after review |

## 3. Performance review

| Path | Work | Cost |
|---|---|---|
| Profile save | Account FOR SHARE, profile by primary key, one write | Primary keys only. FOR SHARE blocks only status changes and logins of the same account, for milliseconds |
| Address write | Account FOR SHARE, advisory lock, count via `idx_user_addresses_user_id`, at most two bulk clears via the partial indexes, one write | Contention only between one person's own parallel requests |
| Address list | One query via `idx_user_addresses_user_id`, at most 20 rows | No paging needed, no N+1 |
| Support view | Existence check, profile, address list | Three indexed reads |
| Export | Account, roles, sessions, memberships, profile, addresses: six indexed reads in one read-only transaction | One connection; rate limited |
| Erasure | Two bulk deletes by owner | `pk_user_profiles`, `idx_user_addresses_user_id` |

`QueryPlanIT` checks seven new plans over 5 000 accounts with a profile and two addresses each.

## 4. Production quality checklist

| Question | Answer |
|---|---|
| Loosely coupled? | Yes. `profile -> rbac -> common` only; auth calls profile for erasure and the export; profile's internals are private (`ModuleBoundariesTest`) |
| Authorization secure? | Yes, see section 2: no IDs to guess for own data, owner-scoped addresses, a separate permission for staff, logged |
| Indexes and constraints correct? | Yes: CHECKs on every format, partial unique indexes for defaults, cascades; `ProfileSchemaIT` |
| Queries efficient? | Yes, see section 3 |
| Sufficiently tested? | Unit, web slice, schema, HTTP, a real race (erasure vs. save), parallel limit, a full journey with log audit |
| Understandable? | PROFILE.md (rules, locks, the race), Javadoc on each rule, this review |
| Privacy by design? | Data minimisation (optional fields), separate permission, access log, export, erasure that wins every race |
| APIs consistent? | Same envelope and Problem Details; PUT replaces; `version` everywhere; Postman collection checked against OpenAPI |
