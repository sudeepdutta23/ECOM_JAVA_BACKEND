# Phase 18 review: Production readiness

- Date: 2026-11-06
- Scope: an audit of the production configuration, the runbook, the list of blockers
- Result: the code is ready for staging; real customers need the blockers in [PRODUCTION-READINESS.md](../PRODUCTION-READINESS.md) closed

## 1. Defects found

| Defect | Severity | Effect | Fix | Evidence |
|---|---|---|---|---|
| `server.forward-headers-strategy: framework` in production | High | The client address came from the left of `X-Forwarded-For`, which the client controls. A random value per request gave a fresh rate-limit bucket (login throttling bypassed) and forged the address in the audit log and session records | `native`: Tomcat reads from the right, stopping at the first untrusted address | `ForwardedHeadersIT`: failed with `framework` (401 where 429 was due), passes with `native` |
| No bound on graceful shutdown phases below the orchestrator's limit | Low | The default 30 s equals the usual kill timeout, so a slow request could be killed mid-shutdown | `timeout-per-shutdown-phase: 20s` | Configuration |
| Simulated payments could run unnoticed | Medium | `PAYMENT_PROVIDER=simulated` in production would give goods away for free, with only per-payment log lines at INFO | A WARN at startup | Code |

The first defect existed since the repository's first commit. Every earlier test ran without a proxy and without the prod profile's setting, so the rate-limit tests could not see it.

## 2. Checked and found correct

| Item | Result |
|---|---|
| Liveness and readiness | Neither depends on PostgreSQL or Redis, so an outage of a shared system does not make the orchestrator restart every instance |
| Error responses | No stack traces, messages or binding errors (`server.error.*`) |
| API docs in production | Off unless `API_DOCS_ENABLED` |
| Refresh cookie | `Secure` by default; only the dev profile relaxes it |
| Destructive schema tools | `flyway clean` disabled; Hibernate only validates |
| Mail | STARTTLS required in production |
| Secrets | None with defaults in production; the application refuses to start without them |

## 3. Accepted risks

| Item | Decision |
|---|---|
| Readiness does not check the database | With the database down, instances stay in rotation and answer 500 rather than the load balancer answering 503. Either way requests fail, and no restart storm is possible |
| A single JWT signing key | Rotation causes a brief burst of refreshes, not sign-outs. Two valid keys (JWKS) would remove it |
| A restored backup brings back erased people | The runbook requires re-applying erasures; a list of erased IDs outside the database is an operational task, not built |
| Flyway and the application share a database account | Simple; a separate migration account would stop the application from altering its own schema |
| Spring Boot 4 | Two accepted CVEs until 2027-02-01 (PHASE-17-REVIEW.md) |
| The runbook is unrehearsed | Written against the code, never executed. A restore and a rollback should be rehearsed in staging |
