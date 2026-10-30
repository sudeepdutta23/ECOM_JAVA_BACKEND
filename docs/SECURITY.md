# Security

## Phase 0 baseline

| Control | Implementation |
|---|---|
| No secrets in code | All credentials come from environment variables; `.env` is git-ignored |
| No stack traces to clients | `server.error.include-*` set to never; `GlobalExceptionHandler` returns generic 500 |
| No SQL details to clients | Data integrity errors return a generic 409; details go to logs only |
| Mass-assignment defence | Jackson rejects unknown JSON fields; APIs accept DTOs, never entities |
| Log injection | `RequestIdFilter` accepts only a safe character set for incoming IDs |
| Database exposure | Docker Compose binds PostgreSQL to 127.0.0.1 only |
| Actuator exposure | Only `health` and `info` are exposed; details hidden outside dev |
| Swagger in production | Disabled unless `API_DOCS_ENABLED=true` |

## Phase 1 security infrastructure (Step 1.3)

| Control | Implementation |
|---|---|
| Default deny | Every path needs a valid access token unless listed in `SecurityConfig` |
| Stateless | No HTTP session, no JSESSIONID cookie |
| Access token verification | RS256 only (algorithm pinned), signature, `exp`/`nbf` with 30 s skew, `iss` |
| Signing key | PKCS#8 PEM from `JWT_PRIVATE_KEY_LOCATION`; at least 2048 bits; prod refuses to start without it |
| Password hashing | Argon2id, 19 MiB, 2 iterations, `{argon2}` prefix for future upgrades |
| 401 / 403 bodies | Problem Details from filters; generic messages; `AUTH_TOKEN_EXPIRED` only for expiry |
| Stale tokens on public endpoints | Authorization header ignored on public paths, so `/refresh` works with an expired token attached |
| Headers | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` |

### Generating a signing key

```bash
mkdir keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/jwt-private.pem
```

`keys/` and `*.pem` are git-ignored. In production, mount the key as a secret file and set `JWT_PRIVATE_KEY_LOCATION=file:/run/secrets/jwt-private.pem`.

## Login (Step 1.5)

| Threat | Control |
|---|---|
| Account discovery via login | Unknown email, wrong password and locked account return one identical 401 body |
| Timing side channel | Unknown and locked accounts are checked against a dummy Argon2 hash, so all paths cost one hash |
| Online guessing | 5 consecutive failures lock for 15 min; while locked the real password is never checked |
| Lost counter updates | Failure counter is one atomic `UPDATE`; proven with parallel requests |
| Silent attacks | The owner is emailed exactly once when the account locks |
| Outdated hashes | Hashes from older algorithms are re-hashed with Argon2id on the next successful login |
| Refresh token theft via XSS | Token only in an `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` cookie, never in the body |

**Known limitation:** a burst of parallel guesses all read "not locked" before any of them commits, so an attacker can get a few more than 5 guesses in that burst. The account still ends locked. Per-IP rate limiting in Step 1.8 closes this.

## Sessions (Step 1.6)

| Threat | Control |
|---|---|
| Stolen refresh token | Single use: every refresh rotates it. Replaying a rotated token revokes the whole family and emails the owner |
| Race on one token | `SELECT ... FOR UPDATE` serializes concurrent refreshes; exactly one wins (proven with 5 parallel requests) |
| Lost revocation on error | Rotation returns a result and throws only after commit, so reuse revocation is never rolled back |
| Deactivated user keeps session | Refresh checks the account is ACTIVE and kills the token otherwise |
| Logout not effective | Refresh token revoked server-side; cookie deleted with matching path |
| Logout-all | Bulk revoke of the user's live tokens only; other users untouched |

**Accepted trade-offs**

- Access tokens stay valid for up to 15 minutes after logout or logout-all (ADR-003). A test documents this.
- Refresh tokens slide: each rotation gets a fresh 7 days, so an active session never expires. An absolute session cap (for example 30 days per family) is a possible later hardening.

## Passwords and account (Step 1.7)

| Threat | Control |
|---|---|
| Hijacked session changes the password | Current password required; wrong answers count toward the same lockout as login |
| Attacker keeps a session after the victim changes password | Change and reset revoke every refresh token; the changing device gets a fresh session |
| Silent takeover | Owner emailed on every password change, reset and deactivation |
| Reset link abuse | 256-bit, single use, 30 minutes, hashed in DB; a new link cancels older ones; policy failure does not burn it |
| Account discovery via forgot | Identical 202 for known and unknown emails |
| IDOR (acting on another user) | User ID taken only from the verified token's `sub`; unknown body fields rejected |
| Data leakage from `/me` | DTO projection selects only allow-listed columns; the password hash never leaves the database |

## Rate limiting, CORS and headers (Step 1.8)

### Per-IP rate limits (token bucket, Bucket4j)

| Rule | Endpoints (POST) | Limit per IP |
|---|---|---|
| `login` | `/auth/login` | 10 per minute |
| `email-sending` | `/auth/register`, `/auth/email/resend-verification`, `/auth/password/forgot` | 5 per 15 minutes, shared |
| `credential-actions` | `/auth/refresh`, `/auth/email/verify`, `/auth/password/reset`, `/auth/password/change`, `/auth/me/deactivate` | 30 per minute |

- Rejections: `429 TOO_MANY_REQUESTS` with `Retry-After`; `X-RateLimit-Limit` and `X-RateLimit-Remaining` on every limited response.
- The filter runs before Spring Security, so a rejected request costs no JWT parsing, database work or hashing.
- Buckets live in a bounded Caffeine cache (100,000 clients, expire after the longest period), so an IP flood cannot exhaust memory.
- This closes the Step 1.5 gap: one IP can no longer burst many parallel guesses at an account.

**Limitations:** limits are per instance (Phase 11 moves buckets to Redis); botnets with many IPs need a gateway or WAF; users behind one corporate NAT share a budget.

### CORS

- Explicit origin allow-list from `CORS_ALLOWED_ORIGINS`; empty by default, so production allows no cross-origin calls until configured. Dev allows `localhost:3000` and `localhost:5173`.
- Credentials allowed (the SPA needs the refresh cookie); never combined with `*`.
- CORS relaxes the browser's same-origin policy; it is not access control. Authentication still guards every endpoint.

### Response headers

| Header | Value | Purpose |
|---|---|---|
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'` | JSON never runs scripts or loads in frames (Swagger UI exempt) |
| `Strict-Transport-Security` | 1 year, include subdomains | Browsers refuse plain HTTP after the first HTTPS visit (sent only over HTTPS) |
| `Permissions-Policy` | camera, microphone, geolocation, payment off | No device features from API responses |
| `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, `Cache-Control: no-store` | from Step 1.3 | Clickjacking, MIME sniffing, URL leakage, token caching |

## Phase 1 review findings (Step 1.9)

Three issues were found and fixed; details and measurements are in [the Phase 1 review](reviews/PHASE-1-REVIEW.md).

| Finding | Fix |
|---|---|
| PostgreSQL error text (with email values) reached logs | Log constraint name or SQLSTATE only |
| ~45 ms timing difference revealed registered emails | 400 ms floor on every invalid-credentials response |
| Unbounded parallel Argon2 could exhaust memory | `BoundedPasswordEncoder`: one hash per core, 503 when saturated |

Set `app.security.password-hashing.max-concurrent` and `app.auth.failed-login-min-duration` per environment after measuring on production hardware.

## Known gaps, closed in later phases

| Gap | Phase |
|---|---|
| Login, registration and token endpoints not built yet | 1 (Steps 1.4 to 1.7) |
| No roles or permissions | 2 |
| Rate limits are per instance, not shared | 11 (Redis) |
| No HTTPS (terminated at the load balancer in production) | 16 to 18 |
| No security headers (HSTS, CSP...) | 1 via Spring Security |

## Never log

Passwords, password hashes, JWTs, refresh tokens, reset tokens, secrets, full card or ID numbers.
