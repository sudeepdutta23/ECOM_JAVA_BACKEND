# Testing

## Layers

| Kind | Naming | Runs in | Needs Docker | Tools |
|---|---|---|---|---|
| Unit | `*Test` | `mvn test` | no | JUnit 5, Mockito, AssertJ |
| Web slice | `*Test` with `@WebMvcTest` | `mvn test` | no | MockMvc |
| Integration | `*IT` | `mvn verify` | yes | `@SpringBootTest`, Testcontainers PostgreSQL |

Surefire runs `*Test`. Failsafe runs `*IT`.

`pom.xml` pins Testcontainers to 1.21.4. The 1.21.3 release managed by Spring Boot 3.5.7 cannot talk to Docker Engine 29 and fails with "Could not find a valid Docker environment" and an HTTP 400. Remove the override once the Spring Boot version manages 1.21.4 or newer.

## Principles

- Integration tests use a real PostgreSQL, never H2, because constraints, types and SQL dialect must match production.
- Time-dependent logic receives a `Clock` so tests use a fixed clock.
- Each test is independent and leaves no shared state.

## Phase 0 tests

| Test | What it proves |
|---|---|
| `RequestIdFilterTest` | IDs are generated, reused when safe, rejected when unsafe, and removed from the MDC |
| `GlobalExceptionHandlerTest` | Every error type maps to the correct status and code without leaking internals |
| `SystemControllerTest` | Ping returns the envelope; a wrong method returns Problem Details |
| `ApplicationIT` | Full app starts on PostgreSQL, Flyway runs, health is UP, OpenAPI is published |

## Phase 1 tests

| Test | What it proves |
|---|---|
| `AuthSchemaIT` | Every V2 constraint rejects bad data at the database level |
| `UuidV7Test` | IDs carry version 7, the right timestamp, sort by time, and stay unique |
| `UserTest`, `RefreshTokenTest`, `AccountTokenTest` | Entity rules: normalization, state changes, idempotent revoke, no secrets in `toString` |
| `UserRepositoryIT` | UUID v7 and audit fields on save, unique email, optimistic locking, atomic failed-login counter with 20 parallel threads |
| `RefreshTokenRepositoryIT` | Bulk revocation scope, dirty-checking persistence, `SELECT ... FOR UPDATE` really blocks a second locker |
| `AccountTokenRepositoryIT` | Invalidating older links of one purpose only |

| `RsaKeyLoaderTest`, `AccessTokenIssuerTest`, `PasswordEncoderConfigTest` | Key loading rules, token claims and expiry, Argon2id parameters |
| `ProblemDetailsSecurityHandlersTest`, `SecurityIT` | 401/403 bodies; real-HTTP attacks with forged, expired, foreign and `alg=none` tokens |
| `OpaqueTokensTest`, `PasswordPolicyTest`, `MaskingTest`, `AsyncConfigTest` | Token format and hashing, password rules, log masking, request ID in async threads |
| `AccountTokenServiceTest`, `RegistrationServiceTest` | Token issue/consume rules; registration branches incl. duplicate and race handling |
| `AuthControllerTest` | HTTP contract: 202/200, validation, unknown fields, JSON-only |
| `RegistrationIT` | Full journey with async email: register, verify once, resend, duplicates, weak password, 5 parallel registrations |
| `AuthenticationServiceTest`, `RefreshTokenServiceTest`, `RefreshTokenCookieTest` | Every login branch, dummy-hash timing, lock notification, hash upgrade, cookie attributes |
| `SessionIT` | Rotation chains, theft replay kills the family and emails the owner, other devices unaffected, 5-way refresh race, logout, logout-all, access token lifetime after logout |
| `PasswordServiceTest`, `AccountServiceTest`, `PasswordAndAccountControllerTest` | Change/forgot/reset branches, lockout on wrong current password, token not burned on policy failure, `/me` allow-list, deactivation |
| `AccountLifecycleIT` | Change keeps this device and ends others, lockout via change endpoint, full forgot/reset, single-use and expiring links, reset unlocks, `/me`, deactivation |
| `RateLimitFilterTest` | Capacity, 429 shape and headers, per-IP and per-rule buckets, refill with a manual clock, unlisted paths untouched |
| `RateLimitIT` | Real filter chain in its own context: 11th login and 6th email-sending call refused |
| `SecurityIT` (CORS and headers) | Allowed and refused preflights, exposed headers, CSP on API but not Swagger UI, Permissions-Policy |
| `AuthJourneyIT` | One user's full life cycle, plus an audit of all log output: no password, token, hash or full email anywhere |
| `QueryPlanIT` | `EXPLAIN` of 21 hot auth, rbac and user-admin queries (with seq scans disabled) proves each one can use its index |
| `UserSearchTest`, `UserAdminControllerTest` | LIKE escaping; defaults, size cap and page validation reported per parameter, enum values listed without Java type names, `user:read` required |
| `UserAdminIT` | Case-insensitive prefix search newest first, `%` and `_` matched literally, page totals and past-the-end, combined filters, no credentials in responses, detail with roles, sessions, lock and status change |
| `UserAdminServiceTest` | Order of checks (manage rule, last admin, then revoke), self-action, escalation, invalid status, nothing changes on refusal, atomic unlock, sign-out reason |
| `UserAdminActionsIT` | Suspension ends sessions and blocks sign-in without revealing the reason (also not in logs), reactivation after suspension and self-deactivation, unlock after a real lockout, sign-out of two devices, a support role limited to customers, last admin kept |
| `SessionServiceTest`, `SessionsIT` | One session per sign-in, `current` from the `sid` claim, same ID across rotation, ending another device only, ending the current one like logout, 404 for other users' sessions, logout-all empties the list |
| `UserManagementSchemaIT` | V4: suspension needs a reason, status and revoke-reason checks, SET NULL on the actor, ADMIN holds `user:*`, one email index serving equality and prefix |
| `BoundedPasswordEncoderTest` | Concurrency cap honoured, fails fast with 503, permits always returned |
| `RbacSchemaIT` | V3 constraints, seed data, and the V2-to-V3 upgrade backfill in a scratch schema |
| `PermissionTest`, `PermissionCatalogVerifierTest` | Codes valid and in sync with constants; startup refuses a drifted catalogue |
| `JwtAuthoritiesConverterTest`, `PermissionControllerTest` | Claims become authorities; `@PreAuthorize` allows, denies with 403, and ignores bare role names |
| `AuthorizationIT` | CUSTOMER on registration, claims in tokens, 403 for customers, promotion visible in `/me` at once and in the token after refresh |
| `RoleManagementServiceTest`, `RoleControllerTest` | Every guard (escalation before and after edit, system roles, in-use, stale version, unknown permission); HTTP contract incl. 201 Location, immutable code, 403/404/400 |
| `RoleManagementIT` | Full CRUD, lost-update protection, an editor without `role:assign` blocked from granting or stripping it, holders gaining permissions at refresh |
| `RoleAssignmentServiceTest`, `UserRoleControllerTest` | Self-modification, escalation in both directions, CUSTOMER kept, last active admin, lock taken before checks, bootstrap outcomes; 204/403/409 contract and `role:assign` required |
| `RoleAssignmentIT` | Grant and revoke idempotent and visible after refresh, 404s, an assigner limited to its own power, last admin kept, two admins demoting each other at once leave one, bootstrap only while no active admin exists |
| `AdminJourneyIT` | Phase 2 as one story: bootstrap, delegation, the only admin cannot leave, hand-over, stale token refused for management, deactivation rules, plus a log audit |
| `LoginIT` | Tokens and cookie over HTTP, hashed storage, identical failure bodies, lockout and expiry, one lock email, parallel guessing, BCrypt upgrade |

Integration tests replace the email transport with `RecordingEmailSender` and wait for async delivery with Awaitility.

Repository tests commit for real instead of rolling back, because locking and concurrency only behave realistically across separate transactions. They clean up their own rows afterwards.
