# Production readiness (Phase 18)

Review: [reviews/PHASE-18-REVIEW.md](reviews/PHASE-18-REVIEW.md).

**Verdict: the code is ready for a staging environment, not yet for real customers.** The blockers below are missing integrations and operational setup, not defects in what exists.

## 1. Blockers before real customers

| # | Blocker | Why it blocks | What closes it |
|---|---|---|---|
| 1 | No real payment provider | Only `SimulatedPaymentGateway` exists: with it nothing is charged, and without it the application does not start | An implementation of the `PaymentGateway` port (Stripe, Adyen, ...), with webhooks and reconciliation |
| 2 | Uploaded files on a local disk | Several instances would each see different images | An S3 implementation of the `FileStorage` port |
| 3 | No backups configured | A lost database loses every order | Managed PostgreSQL with point-in-time recovery, and one restore actually rehearsed |
| 4 | No hosting environment | Nothing to deploy to; the pipeline stops at the registry | Environment with TLS at the load balancer, secrets store, management port private |
| 5 | Load never measured | Capacity and the pool size are estimates | The k6 script in [modules/PERFORMANCE.md](modules/PERFORMANCE.md), against staging |
| 6 | No alerts wired | The metrics exist but nobody gets paged | Prometheus, Alertmanager and the rules in [modules/OBSERVABILITY.md](modules/OBSERVABILITY.md) |
| 7 | Legal and tax | Prices are EUR with no VAT handling; privacy policy and backup retention are open | Business decisions, then code where needed |

## 2. Before go-live, in the repository's settings

- Merge `feature/rbac` to `main`, and confirm the CI workflow's first runs are green.
- Protect `main`: require the `Build and test` and `Image` checks and a review.

## 3. Checklist of what exists

| Area | Item | Evidence |
|---|---|---|
| Security | Passwords (Argon2), JWT signing, refresh token rotation and hashing | [modules/AUTH.md](modules/AUTH.md), `SessionIT` |
| Security | Authorization on every endpoint, tenant isolation | `TenantIsolationIT`, `SecurityIT` |
| Security | Client address cannot be spoofed (rate limits, audit, sessions) | `ForwardedHeadersIT` |
| Security | Metrics and actuator private | `ObservabilityIT` |
| Security | Image scanned on every build, exceptions expire | [modules/CICD.md](modules/CICD.md) |
| Data | Schema only by Flyway; Hibernate validates | `ddl-auto: validate` |
| Data | Audit log append-only | `AuditIT` |
| Data | Account erasure, personal data export | `AccountErasureIT` |
| Reliability | Durable emails, unpaid order expiry | `EmailOutboxIT`, `JobsIT` |
| Reliability | Redis outage does not take the site down | `JsonCacheTest`, `RateLimitFilterTest` |
| Reliability | Graceful shutdown, bounded at 20 s | Phase 16 smoke test |
| Performance | No N+1 on hot lists, an index for every hot query, server-side timeouts | `QueryCountIT`, `QueryPlanIT`, `DatabaseGuardsIT` |
| Operations | Metrics, JSON logs, trace IDs | [modules/OBSERVABILITY.md](modules/OBSERVABILITY.md) |
| Delivery | Tested image from every commit | [modules/CICD.md](modules/CICD.md) |

## 4. Runbook

### Deploy

1. Pick a published tag (`sha-<commit>` or a version). Never build on the server.
2. Set the variables of [modules/DOCKER.md](modules/DOCKER.md).
3. Roll out one instance at a time, waiting for `/actuator/health/readiness` on the management port. Flyway migrates at startup; concurrent instances wait on its lock.

The load balancer must be the only way to reach port 8080, and the only trusted proxy: a peer that can reach the port directly from a private address could set its own `X-Forwarded-For`. If its address is public, set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`.

### Roll back

Deploy the previous tag. Migrations are not reversed, so the previous version must work with the new schema: every migration is **expand, then contract**. Add a column in one release; stop using the old one; drop it in a later release. A migration that cannot follow this needs a maintenance window.

### Restore a backup

1. Stop the application (or scale to zero).
2. Restore to a new database with point-in-time recovery, and point `DB_HOST` at it.
3. Start one instance. Flyway validates the schema history.
4. After a restore, re-apply erasures made since the backup's time: a restored backup brings back people who asked to be erased. Keep a list of erased user IDs outside the database for this.
5. Reconcile orders with the payment provider for the lost period.

### Rotate secrets

| Secret | How | Effect |
|---|---|---|
| JWT signing key | Replace the key file, roll out | Tokens signed with the old key fail. Clients get a 401 and refresh with their refresh token, which is not a JWT, so nobody is signed out. During the rollout, old and new instances reject each other's tokens: expect a short burst of refreshes |
| Database password | Add a second credential, roll out, remove the first | None if done in that order |
| Redis password, SMTP password | Change, roll out | Redis fails open during the change: rate limits and cache degrade, the site stays up |

If the signing key leaked: rotate at once, and if the attacker could have held sessions, end all of them: `UPDATE refresh_tokens SET revoked_at = now(), revoke_reason = 'ADMIN_SIGN_OUT' WHERE revoked_at IS NULL`. Everyone signs in again.

### First look in an incident

| Symptom | Look at | Likely |
|---|---|---|
| 5xx rising | `http_server_requests_seconds_count{outcome="SERVER_ERROR"}` by `uri`, then logs by `requestId` or `traceId` | A bug in one route, or the database |
| Slow, no errors | `hikaricp_connections_pending` | Pool exhausted: a slow query or a traffic peak |
| `canceling statement due to statement timeout` in logs | The query in the trace | A bad plan or a missing index |
| Emails late | `emails_outbox_oldest_age_seconds`, `emails_failed_total` | Mail server down; the outbox retries by itself |
| Login failures spike | `auth_logins_total`, `rate_limit_rejected_total` | Credential stuffing; see the masked emails and client IPs in the logs |
| `rate_limit_store_errors_total` rising | Redis | Rate limits are off while Redis is down: restore it first |
| A job not running | `tasks_scheduled_execution_seconds`, the `shedlock` table | `lock_until` in the future after a crash frees itself within `lockAtMostFor` |
