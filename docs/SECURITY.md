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

## Known gaps, closed in later phases

| Gap | Phase |
|---|---|
| No authentication: every endpoint is public | 1 |
| No authorization | 2 |
| No rate limiting | 1 |
| No CORS policy | 1 |
| No HTTPS (terminated at the load balancer in production) | 16 to 18 |
| No security headers (HSTS, CSP...) | 1 via Spring Security |

## Never log

Passwords, password hashes, JWTs, refresh tokens, reset tokens, secrets, full card or ID numbers.
