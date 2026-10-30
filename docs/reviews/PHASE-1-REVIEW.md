# Phase 1 review: Authentication module

- Date: 2026-10-29
- Scope: everything under `auth/` and the shared `common/` pieces it relies on
- Result: complete; 302 automated tests passing (189 unit, 113 integration)

## 1. What was delivered

| Capability | Endpoint(s) |
|---|---|
| Registration with email verification | `POST /register`, `/email/verify`, `/email/resend-verification` |
| Login with lockout | `POST /login` |
| Rotating refresh tokens with theft detection | `POST /refresh` |
| Logout, logout everywhere | `POST /logout`, `/logout-all` |
| Password change, forgot, reset | `POST /password/change`, `/password/forgot`, `/password/reset` |
| Account view and self-deactivation | `GET /me`, `POST /me/deactivate` |

All under `/api/v1/auth`. Contract: [modules/AUTH.md](../modules/AUTH.md) and the live OpenAPI spec.

## 2. Security review

Method: read every log statement, error path and query; then prove the claims with tests.

### Findings fixed during the review

| # | Finding | Severity | Fix | Proof |
|---|---|---|---|---|
| 1 | Data-integrity errors logged PostgreSQL's message, which contains the offending value (an email address) | Medium (PII in logs) | Log only the constraint name or SQLSTATE | `GlobalExceptionHandlerTest.integrityLogLineNamesTheRuleButNeverTheValue` |
| 2 | Wrong-password logins on real accounts took ~45 ms longer than unknown emails (extra counter write), enabling account discovery by timing | Medium (enumeration) | Every invalid-credentials failure padded to a 400 ms floor | Live re-measurement below; `AuthenticationServiceTest.failedLoginsArePaddedToTheFloorButSuccessesAreNot` |
| 3 | Unbounded concurrent Argon2 hashing: 200 Tomcat threads × 19 MiB ≈ 3.8 GB under a distributed login flood | High (availability) | `BoundedPasswordEncoder` caps concurrent hashes at one per core and fails fast with 503 | `BoundedPasswordEncoderTest` |

Timing before and after fix 2 (24 samples each, local machine):

| Path | Before | After |
|---|---|---|
| Wrong password, real account | median 243 ms, p90 860 ms | median 412 ms, p90 421 ms |
| Unknown email | median 198 ms, p90 247 ms | median 416 ms, p90 422 ms |

### Verified controls

| Area | Status | Evidence |
|---|---|---|
| Password storage | Argon2id, OWASP parameters, upgradeable prefix | `PasswordEncoderConfigTest`, `LoginIT.legacyBcryptHashIsUpgradedToArgon2OnLogin` |
| Token storage | Only SHA-256 hashes of refresh and email tokens in the database | `RegistrationIT.secretsAreStoredOnlyAsHashes`, `LoginIT.refreshTokenIsStoredHashed...` |
| JWT | RS256 pinned; forged, foreign-key, wrong-issuer, expired, `alg=none` all rejected | `SecurityIT` |
| Enumeration | Identical bodies for register, forgot, resend and failed login; equal timing | `RegistrationIT`, `LoginIT`, `AccountLifecycleIT`, fix 2 |
| Brute force | Lockout + per-IP rate limits + hashing bulkhead | `LoginIT`, `RateLimitIT`, `BoundedPasswordEncoderTest` |
| Session theft | Rotation, reuse detection, family revocation, owner email | `SessionIT` |
| Secrets in logs | Full journey audited: no password, token, hash or full email in any log line | `AuthJourneyIT` |
| Mass assignment / IDOR | Unknown JSON fields rejected; user ID only from the token | `PasswordAndAccountControllerTest.userIdInTheBodyIsRejectedNotTrusted` |
| Browser protections | CORS allow-list, CSP, HSTS, frame and MIME protections, HttpOnly SameSite cookie | `SecurityIT`, `RefreshTokenCookieTest` |

### Accepted risks and recommendations

| Item | Decision |
|---|---|
| Access tokens stay valid up to 15 min after logout or password change | Accepted (ADR-003). Option later: short denylist of `jti` in Redis (Phase 11) |
| Rate limits are per instance | Accepted until Phase 11 moves buckets to Redis |
| Email sent after commit can be lost if the process dies | Accepted; user can resend. Transactional outbox in Phase 13 |
| No `aud` claim in access tokens | Add when a second service consumes tokens |
| No request-body size limit on JSON endpoints | Enforce at the gateway in Phase 18; DTO size limits already bound the work |
| No multi-factor authentication | Future enhancement; the token model supports adding it |
| Sliding refresh sessions never hard-expire while in use | Consider a 30-day family cap |

## 3. Performance review

Measured on the developer machine (8 cores, Docker PostgreSQL), dev profile, warm JVM:

| Operation | Median | Notes |
|---|---|---|
| `GET /api/v1/system/ping` | 4 ms | Baseline: filters, security, JSON |
| `GET /api/v1/auth/me` | 11 ms | JWT verify + one projected primary-key query |
| `POST /api/v1/auth/refresh` | 42 ms | Lock + 2 reads + insert + update + JWT signing |
| `POST /api/v1/auth/login` (success) | 249 ms | Dominated by Argon2 |
| `POST /api/v1/auth/login` (failure) | ~415 ms | Deliberate floor (finding 2) |
| One Argon2id verify, single thread | 54 ms | Micro-benchmark |
| Argon2id throughput, 8 or 32 threads | ~31–33 per second | Memory-bandwidth bound; does not scale with threads |

Conclusions:

- **Password hashing is the capacity limit**, by design: about 30 logins per second per instance on this hardware. Scale logins horizontally; everything else is cheap.
- **Every hot query uses its index.** `QueryPlanIT` checks eight query plans, including the locking ones.
- **No N+1 queries** in auth flows. Refresh deliberately uses a separate user read instead of a join fetch, because a locking join would also lock the user row and make every refresh wait behind that user's logins.
- **Login avoids optimistic-lock conflicts** by using atomic UPDATEs for bookkeeping.
- **Hashing happens outside transactions**, so database connections are never held during the 50 ms hash.
- **`/me` uses a DTO projection**: no entity loading, no dirty checking, and no password hash fetched.

## 4. Production quality checklist

| Question | Answer |
|---|---|
| Loosely coupled? | Yes. `auth` depends only on `common`; email goes through the `EmailSender` interface; cross-cutting security lives in `common/security` |
| Authentication secure? | Yes, see section 2; three findings fixed with proof |
| Indexes and constraints correct? | Yes; 13 schema-constraint tests, 8 query-plan tests |
| Queries efficient? | Yes, see section 3 |
| Sufficiently tested? | 302 tests: unit, web slice, repository, security attack, concurrency, journey, log audit |
| Understandable? | Javadoc explains each decision; 9 ADRs; module design doc; this review |
| Scalable? | Stateless API; horizontal scaling; Redis needed for shared rate limits (Phase 11) |
| Observable? | Request ID in every log line, including async email threads; structured auth event logs; health and readiness probes |
| APIs consistent and versioned? | `/api/v1`; one success envelope; RFC 9457 errors with stable codes; OpenAPI checked by a test |
