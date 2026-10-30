# Auth module design

Status: implemented and reviewed (Phase 1 complete, 2026-10-29). See [the Phase 1 review](../reviews/PHASE-1-REVIEW.md). This file is the contract the code follows.

## 1. Scope

| In Phase 1 | Deferred |
|---|---|
| Registration, email verification, resend verification | Roles and permissions (Phase 2) |
| Login, logout, logout from all devices | Admin deactivation and reactivation (Phase 3) |
| Access tokens (JWT) and rotating refresh tokens | Names, addresses, avatars (Phase 5) |
| Change, forgot and reset password | Persistent audit trail (Phase 9) |
| Self-deactivation | Redis-backed distributed rate limits (Phase 11) |
| Failed-login tracking and temporary lockout | Multi-factor authentication |
| Per-IP rate limiting (in memory) | Social login (OAuth2 / OIDC) |

## 2. Components

As built:

```
auth/
  controller/   AuthController            register, verify, resend, login, refresh, logout, logout-all
                PasswordController        password change, forgot, reset
                AccountController         GET /me, deactivate
  service/      RegistrationService       register, verify email, resend
                AuthenticationService     login (with failure-time floor), refresh, logout
                RefreshTokenService       issue / rotate (row lock) / revoke refresh tokens
                PasswordService           change, forgot, reset
                AccountService            current user (projection), deactivate
                AccountTokenService       issue, peek and consume one-time tokens
                FailedLoginRecorder       atomic failure count + lock notification (shared)
                CurrentPasswordCheck      re-confirm password for sensitive actions (shared)
                PasswordPolicy            NIST-style password rules
  security/     AccessTokenIssuer         sign access tokens (RS256)
                JwtConfig, RsaKeyLoader   signing key, encoder, decoder with pinned validators
                PasswordEncoderConfig     Argon2id inside BoundedPasswordEncoder (concurrency cap)
                RefreshTokenCookie        build and clear the HttpOnly cookie
                OpaqueTokens              256-bit tokens and their SHA-256 hashes
                CurrentUser               user ID from the verified token
  repository/   UserRepository (+ UserSummary projection), RefreshTokenRepository, AccountTokenRepository
  entity/       User, RefreshToken, AccountToken, UserStatus, TokenPurpose, RevokeReason
  event/        domain events, handled after commit
  mail/         AuthEmailListener         async emails after commit
  dto/          request and response records

common/security/  SecurityConfig, 401/403 handlers, RateLimitFilter (shared by all modules)
common/mail/      EmailSender interface + SMTP and logging implementations
```

Services are split by use case so no class grows into a "god service".

## 3. Token model

| Token | Format | Lifetime | Stored server-side | Delivered |
|---|---|---|---|---|
| Access | JWT, RS256, claims `sub`, `iat`, `exp`, `jti`, `iss` | 15 min | No | Response body; client sends `Authorization: Bearer` |
| Refresh | 256-bit random, base64url | 7 days | SHA-256 hash | HttpOnly cookie |
| Email verification | 256-bit random | 24 h | SHA-256 hash | Link in email |
| Password reset | 256-bit random | 30 min | SHA-256 hash | Link in email |

**Why SHA-256 and not Argon2 for tokens?** Tokens are already 256 bits of randomness, so brute force is impossible and a slow hash adds nothing. A fast deterministic hash also lets us find the row with an indexed equality lookup. Passwords are different: they are low-entropy and need a deliberately slow hash.

**Refresh cookie:** `refresh_token`, `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=604800`. JavaScript cannot read it, it is never sent to other paths, and it is not sent on cross-site requests.

### Rotation and reuse detection

```
login ───> R1 (family F)
refresh R1 ───> R1 revoked(ROTATED, replaced_by=R2), R2 issued (family F)
refresh R2 ───> R2 revoked(ROTATED), R3 issued
attacker replays R1 ───> R1 is already revoked
                       => REUSE_DETECTED: revoke every token in family F
                       => 401; real user must log in again
```

Concurrency: the token row is locked with `SELECT ... FOR UPDATE` during rotation, so two simultaneous refreshes with the same token cannot both succeed.

## 4. Account lifecycle

```
register ──> PENDING_VERIFICATION ──verify email──> ACTIVE ──deactivate──> DEACTIVATED
                                                     │  ▲
                              5 failed logins ───────┘  └── locked_until passes (15 min)
                              (locked_until set; status unchanged)
```

- Lockout is a time window (`locked_until`), not a status, so it ends on its own and needs no admin.
- Unverified and deactivated accounts cannot log in.

## 5. Endpoints

All paths are under `/api/v1/auth`. All bodies are JSON. Errors use Problem Details (see API.md).

| # | Method | Path | Auth | Success | Purpose |
|---|---|---|---|---|---|
| 1 | POST | `/register` | Public | 202 | Create account, send verification email |
| 2 | POST | `/email/verify` | Public | 200 | Consume verification token, activate account |
| 3 | POST | `/email/resend-verification` | Public | 202 | Send a new verification email |
| 4 | POST | `/login` | Public | 200 + cookie | Get access token and refresh cookie |
| 5 | POST | `/refresh` | Refresh cookie | 200 + cookie | Rotate refresh token, new access token |
| 6 | POST | `/logout` | Refresh cookie | 204 | Revoke this session |
| 7 | POST | `/logout-all` | Bearer | 204 | Revoke every session of the user |
| 8 | POST | `/password/change` | Bearer | 200 + cookie | Change password, revoke other sessions |
| 9 | POST | `/password/forgot` | Public | 202 | Email a reset link |
| 10 | POST | `/password/reset` | Public | 204 | Set new password with reset token |
| 11 | GET | `/me` | Bearer | 200 | Current account summary |
| 12 | POST | `/me/deactivate` | Bearer | 204 | Deactivate own account (needs password) |

**Why verbs in URLs?** Authentication operations are commands, not resources. Modelling them as `POST /sessions` and `DELETE /sessions/{id}` is possible, but every major identity provider uses action endpoints, and they are clearer for clients. Resource style returns in Phase 3 for session listing (`GET /me/sessions`).

**Why `/email/verify` is a POST, not a GET link?** Email scanners and link previewers follow GET links automatically and would consume the token. The email link opens a frontend page, which POSTs the token.

### 5.1 Request and response bodies

```jsonc
// 1. POST /register
{ "email": "alice@example.com", "password": "Correct-Horse-9-Battery" }
// 202
{ "success": true, "message": "If the email can be registered, a verification link has been sent." }

// 2. POST /email/verify
{ "token": "<from email link>" }
// 200
{ "success": true, "message": "Email verified. You can now sign in." }

// 3. POST /email/resend-verification  and  9. POST /password/forgot
{ "email": "alice@example.com" }
// 202, identical response whether or not the account exists

// 4. POST /login
{ "email": "alice@example.com", "password": "Correct-Horse-9-Battery" }
// 200, plus Set-Cookie: refresh_token=...
{ "success": true, "message": "Login successful",
  "data": { "accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 900 } }

// 5. POST /refresh  (no body, cookie only)  -> same body as login

// 8. POST /password/change
{ "currentPassword": "...", "newPassword": "..." }

// 10. POST /password/reset
{ "token": "<from email link>", "newPassword": "..." }

// 11. GET /me
{ "success": true, "message": "Current user",
  "data": { "id": "0192...", "email": "alice@example.com", "status": "ACTIVE",
            "emailVerified": true, "createdAt": "2026-10-27T09:00:00Z" } }

// 12. POST /me/deactivate
{ "password": "..." }
```

### 5.2 Validation rules

| Field | Rules |
|---|---|
| `email` | Required, valid format, max 254 chars; trimmed and lower-cased by the service |
| `password`, `newPassword` | Required, 12 to 128 chars, not equal to the email, not in a small common-password list |
| `currentPassword`, `password` on login | Required, max 128 chars; no strength rules on login, so old accounts still work |
| `token` | Required, 43 chars, base64url alphabet |

Password policy follows NIST SP 800-63B: length over complexity. No forced symbols, no forced rotation.

### 5.3 Status codes and error codes

| Situation | Status | Code |
|---|---|---|
| Invalid DTO | 400 | `VALIDATION_FAILED` |
| Wrong email or password, unknown email, or locked account | 401 | `AUTH_INVALID_CREDENTIALS` |
| Correct password but email not verified | 403 | `AUTH_EMAIL_NOT_VERIFIED` |
| Correct password but account deactivated | 403 | `AUTH_ACCOUNT_DISABLED` |
| Missing, invalid, expired, revoked or reused refresh token | 401 | `AUTH_INVALID_REFRESH_TOKEN` |
| Missing or invalid access token | 401 | `UNAUTHORIZED` |
| Expired access token | 401 | `AUTH_TOKEN_EXPIRED` |
| Invalid, expired or used verification / reset token | 400 | `AUTH_INVALID_ACCOUNT_TOKEN` |
| Wrong current password on change or deactivate | 400 | `AUTH_INVALID_CURRENT_PASSWORD` |
| New password fails policy | 400 | `VALIDATION_FAILED` |
| Too many requests from one IP | 429 | `TOO_MANY_REQUESTS` + `Retry-After` |

The 403 codes are only returned after the password was proven correct, so they reveal nothing to an attacker who does not know the password.

### 5.4 Transaction boundaries

| Operation | Transaction | Notes |
|---|---|---|
| register | One write tx: insert user + account token | Email sent **after commit** so a rollback never sends mail |
| verify email | One write tx: consume token + activate user | Token row locked to prevent double use |
| login | One write tx | Failed-attempt counter must commit even though the call returns 401, so the service returns a result instead of throwing inside the tx. Counter uses an atomic `UPDATE ... SET failed_login_count = failed_login_count + 1` |
| refresh | One write tx, `SELECT ... FOR UPDATE` on the token | Reuse detection revokes the family in the same tx |
| logout-all, password change/reset, deactivate | One write tx | Bulk `UPDATE refresh_tokens SET revoked_at ... WHERE user_id = ? AND revoked_at IS NULL` |
| me | Read-only tx | `@Transactional(readOnly = true)` |

Password hashing (about 50 to 100 ms) happens **before** the transaction opens where possible, so a database connection is not held while the CPU hashes.

## 6. Security controls

| Threat | Control |
|---|---|
| Password database leak | Argon2id with per-password salt (ADR-007) |
| Token database leak | Only SHA-256 hashes of tokens are stored |
| Brute force on one account | 5 failures in a row lock the account for 15 min |
| Credential stuffing across accounts | Per-IP rate limit on login, register, forgot, resend |
| Lockout used as denial of service | Lock is temporary; IP rate limit slows the attacker first |
| User enumeration | Same 202 for register/forgot/resend; same 401 for unknown email, wrong password and locked; dummy hash on unknown email so timing matches |
| Stolen refresh token | Rotation + reuse detection revokes the family |
| XSS stealing tokens | Refresh token is HttpOnly; access token lives only in memory and expires in 15 min |
| CSRF | Refresh cookie is SameSite=Strict and path-scoped; state-changing endpoints require JSON, which forces a CORS preflight cross-origin |
| Forged JWT | RS256 signature checked; `alg` fixed by the decoder, so `alg=none` is rejected (ADR-008) |
| Logout not effective | Refresh tokens revoked server-side; access token dies within 15 min |
| Secrets in logs | Passwords, tokens and hashes are never logged; emails are masked (`a***@example.com`) |
