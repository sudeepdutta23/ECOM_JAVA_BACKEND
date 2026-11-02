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
| 400 | `VALIDATION_FAILED` | Bean Validation failed, in the body or in query and path parameters (`errors[].field` names the parameter) |
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
| 403 | `AUTH_ACCOUNT_DISABLED` | Correct password, account deactivated or suspended (the internal reason is never returned) |
| 401 | `AUTH_INVALID_REFRESH_TOKEN` | Refresh cookie missing, unknown, expired, revoked or reused; sign in again |
| 400 | `AUTH_INVALID_CURRENT_PASSWORD` | Wrong current password on change or deactivate (counts toward lockout) |
| 409 | `CONCURRENT_MODIFICATION` | Stale `version`, or another write won the race; reload and retry |
| 403 | `RBAC_ESCALATION_DENIED` | The role, or the account you act on, has permissions you do not hold |
| 409 | `RBAC_SYSTEM_ROLE` | System roles are read-only |
| 409 | `RBAC_ROLE_IN_USE` | Role still assigned; revoke it first |
| 403 | `RBAC_SELF_MODIFICATION_DENIED` | You tried to grant or revoke one of your own roles |
| 409 | `RBAC_LAST_ADMIN` | Revoking, deactivating or suspending would leave no active administrator |
| 403 | `USER_SELF_ACTION_DENIED` | Admin account actions on your own account; use your own account endpoints |
| 409 | `USER_INVALID_STATUS` | The action does not fit the account's status (for example suspending a suspended account) |

### Client guidance for refresh

- Refresh "single-flight": if several requests get `AUTH_TOKEN_EXPIRED` at once, run ONE refresh and let the others wait for it. Two parallel refreshes with the same cookie look like token theft and end the session.
- On `AUTH_INVALID_REFRESH_TOKEN`, send the user to the login page.
| 429 | `TOO_MANY_REQUESTS` | Rate limit (Phase 1) |
| 500 | `INTERNAL_ERROR` | Anything unexpected; details only in logs |

## Pagination

List endpoints take `page` (from 0) and `size` (default 20, maximum 100) and return, inside `data`:

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 137, "totalPages": 7 }
```

The order is fixed per endpoint and documented with it. A page past the end is empty. See ADR-011.

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
| GET | `/api/v1/auth/me/sessions` | Bearer | My signed-in devices; `current` marks this one |
| DELETE | `/api/v1/auth/me/sessions/{sessionId}` | Bearer | Sign out one device; 204; 404 if not mine |
| POST | `/api/v1/auth/me/deactivate` | Bearer | Needs current password; ends all sessions; 204; 409 `RBAC_LAST_ADMIN` for the last active admin |
| POST | `/api/v1/auth/me/erase` | Bearer | Irreversible. Needs current password; anonymises the account, deletes sessions, links and roles; 204; 409 `RBAC_LAST_ADMIN` |
| GET | `/api/v1/admin/permissions` | Bearer + `role:read` | Permission catalogue |
| GET | `/api/v1/admin/roles` | Bearer + `role:read` | Roles with permissions |
| GET | `/api/v1/admin/roles/{roleId}` | Bearer + `role:read` | One role |
| POST | `/api/v1/admin/roles` | Bearer + `role:write` | Create custom role; 201 + Location |
| PUT | `/api/v1/admin/roles/{roleId}` | Bearer + `role:write` | Replace name, description, permissions; send `version` |
| DELETE | `/api/v1/admin/roles/{roleId}` | Bearer + `role:write` | Delete unassigned custom role; 204 |
| GET | `/api/v1/admin/users/{userId}/roles` | Bearer + `role:read` | A user's roles, with who granted them and when |
| PUT | `/api/v1/admin/users/{userId}/roles/{roleId}` | Bearer + `role:assign` | Grant; idempotent; no body; 204 |
| DELETE | `/api/v1/admin/users/{userId}/roles/{roleId}` | Bearer + `role:assign` | Revoke; idempotent; 204 |
| GET | `/api/v1/admin/users?status=&email=&page=&size=` | Bearer + `user:read` | Accounts, newest first; email is a case-insensitive prefix; size 1 to 100 |
| GET | `/api/v1/admin/users/{userId}` | Bearer + `user:read` | One account: status change, lockout, active sessions, roles |
| POST | `/api/v1/admin/users/{userId}/suspend` | Bearer + `user:manage` | `{reason}`; ACTIVE only; ends all sessions; returns the account |
| POST | `/api/v1/admin/users/{userId}/reactivate` | Bearer + `user:manage` | SUSPENDED or DEACTIVATED only; returns the account |
| POST | `/api/v1/admin/users/{userId}/unlock` | Bearer + `user:manage` | Ends a failed-login lockout; idempotent |
| POST | `/api/v1/admin/users/{userId}/sign-out` | Bearer + `user:manage` | Ends every session; idempotent |
| POST | `/api/v1/admin/users/{userId}/erase` | Bearer + `user:erase` | Irreversible anonymisation in any status; 409 `USER_INVALID_STATUS` if already erased, `RBAC_LAST_ADMIN` |

`GET /api/v1/auth/me` also returns `roles` and `permissions` (read from the database, so current even before a token refresh).
| GET | `/actuator/health` | Public | Health, including database |
| GET | `/actuator/info` | Public | Build information |
