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
| 429 | `TOO_MANY_REQUESTS` | Rate limit (Phase 1) |
| 500 | `INTERNAL_ERROR` | Anything unexpected; details only in logs |

## Request ID

Send `X-Request-Id` (8 to 64 characters, letters, digits, `.`, `_`, `-`) to correlate calls. Otherwise the server generates one. It is always returned in the response header and body.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/api/v1/system/ping` | Public | Connectivity check |
| GET | `/actuator/health` | Public | Health, including database |
| GET | `/actuator/info` | Public | Build information |
