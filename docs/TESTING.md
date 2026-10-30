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
| `QueryPlanIT` | `EXPLAIN` of 8 hot queries (with seq scans disabled) proves each one can use its index |
| `BoundedPasswordEncoderTest` | Concurrency cap honoured, fails fast with 503, permits always returned |
| `LoginIT` | Tokens and cookie over HTTP, hashed storage, identical failure bodies, lockout and expiry, one lock email, parallel guessing, BCrypt upgrade |

Integration tests replace the email transport with `RecordingEmailSender` and wait for async delivery with Awaitility.

Repository tests commit for real instead of rolling back, because locking and concurrency only behave realistically across separate transactions. They clean up their own rows afterwards.
