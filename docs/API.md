# API conventions

## Versioning

All business endpoints live under `/api/v1/...`. A breaking change creates `/api/v2/...` for the affected resource; non-breaking additions stay in v1.

## Success responses

```json
{
  "success": true,
  "message": "Service is running",
  "data": { "status": "UP", "service": "ecommerce-backend", "serverTime": "2026-10-26T10:15:30Z" },
  "timestamp": "2026-10-26T10:15:30.123Z",
  "requestId": "9f1c2a4e-1b7d-4c55-9a0e-3f2b8c6d7e81"
}
```

## Error responses (RFC 9457 Problem Details)

`Content-Type: application/problem+json`

```json
{
  "type": "urn:problem-type:validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid",
  "instance": "/api/v1/auth/register",
  "code": "VALIDATION_FAILED",
  "timestamp": "2026-10-26T10:15:30.123Z",
  "requestId": "9f1c2a4e-1b7d-4c55-9a0e-3f2b8c6d7e81",
  "errors": [ { "field": "email", "message": "must be a well-formed email address" } ]
}
```

Clients branch on `code`, never on `detail` or `title`.

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation failed |
| 400 | `MALFORMED_REQUEST` | Unparseable JSON, unknown field, wrong type |
| 401 | `UNAUTHORIZED` | Missing or invalid credentials (Phase 1) |
| 403 | `FORBIDDEN` | Authenticated but not allowed (Phase 2) |
| 404 | `RESOURCE_NOT_FOUND` | Unknown route or resource |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method |
| 409 | `DUPLICATE_RESOURCE` | Uniqueness rule checked by a service |
| 409 | `DATA_INTEGRITY_VIOLATION` | Database constraint caught a race |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Wrong Content-Type |
| 422 | `BUSINESS_RULE_VIOLATION` | Valid input that breaks a business rule |
| 400 | `AUTH_INVALID_ACCOUNT_TOKEN` | Email link unknown, expired, used, or for another purpose |
| 401 | `AUTH_TOKEN_EXPIRED` | Access token expired; call refresh |
| 401 | `AUTH_INVALID_CREDENTIALS` | Unknown email, wrong password, or temporarily locked account |
| 403 | `AUTH_EMAIL_NOT_VERIFIED` | Correct password, email not yet verified |
| 403 | `AUTH_ACCOUNT_DISABLED` | Correct password, account deactivated |
| 401 | `AUTH_INVALID_REFRESH_TOKEN` | Refresh cookie missing, unknown, expired, revoked or reused; sign in again |
| 400 | `AUTH_INVALID_CURRENT_PASSWORD` | Wrong current password on change or deactivate (counts toward lockout) |

### Client guidance for refresh

- Refresh "single-flight": if several requests get `AUTH_TOKEN_EXPIRED` at once, run ONE refresh and let the others wait for it. Two parallel refreshes with the same cookie look like token theft and end the session.
- On `AUTH_INVALID_REFRESH_TOKEN`, send the user to the login page.
| 429 | `TOO_MANY_REQUESTS` | Rate limit (Phase 1) |
| 500 | `INTERNAL_ERROR` | Anything unexpected; details only in logs |

## Request ID

Send `X-Request-Id` (8 to 64 characters, letters, digits, `.`, `_`, `-`) to correlate calls. Otherwise the server generates one. It is always returned in the response header and body.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/api/v1/system/ping` | Public | Connectivity check |
| POST | `/api/v1/auth/register` | Public | Create account; always 202 |
| POST | `/api/v1/auth/email/verify` | Public | Activate account with emailed token |
| POST | `/api/v1/auth/email/resend-verification` | Public | New verification link; always 202 |
| POST | `/api/v1/auth/login` | Public | Access token in body, refresh token in HttpOnly cookie |
| POST | `/api/v1/auth/refresh` | Refresh cookie | Rotate refresh token, new access token |
| POST | `/api/v1/auth/logout` | Refresh cookie (optional) | End this session, delete cookie; always 204 |
| POST | `/api/v1/auth/logout-all` | Bearer | End every session of the user; always 204 |
| POST | `/api/v1/auth/password/change` | Bearer | Needs current password; ends other sessions, returns a new one |
| POST | `/api/v1/auth/password/forgot` | Public | Email a 30-minute reset link; always 202 |
| POST | `/api/v1/auth/password/reset` | Public | New password with reset token; ends all sessions; 204 |
| GET | `/api/v1/auth/me` | Bearer | Current account summary |
| POST | `/api/v1/auth/me/deactivate` | Bearer | Needs current password; ends all sessions; 204 |
| GET | `/actuator/health` | Public | Health, including database |
| GET | `/actuator/info` | Public | Build information |
