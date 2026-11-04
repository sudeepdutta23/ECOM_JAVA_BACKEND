# Phase 15 review: Performance

- Date: 2026-11-04
- Scope: N+1 guard (`QueryCountIT`), HTTP caching of the storefront, database timeouts, threading decision (ADR-016)
- Result: complete (see [TESTING.md](../TESTING.md))

## 1. Performance review

| Area | Finding | Evidence |
|---|---|---|
| N+1 on hot lists | None: every list loads associations in one batch per page | `QueryCountIT` (6 endpoints, flat counts) |
| Indexes | Every hot query uses an index | `QueryPlanIT` (82 plans) |
| Repeated public reads | Answered by CDN and browser caches within `max-age`, then 304 | `CatalogCacheIT.theCategoryListIsHttpCacheableAndRevalidatesWithItsEtag` |
| Runaway statements | Cancelled by PostgreSQL after 15 s | `DatabaseGuardsIT` |
| Leaked transactions | Ended after 60 s idle | Configuration (`DatabaseGuardsIT` checks it is set) |

No defect was found.

## 2. Security review

| Risk | Handling |
|---|---|
| A shared cache storing personal data | Only storefront data is `public`; personal reads keep `no-store` (`CatalogCacheIT.personalReadsAreNeverMarkedPublic`) |
| A suspended seller still served from caches | `max-age` 30 s for products; the server-side cache is evicted at once |
| BREACH (compression of secrets next to reflected input) | No response compression in the application; compress at the CDN for the storefront only |
| A timeout lifted for a migration leaking into the pool | `SET LOCAL` only (`DatabaseGuardsIT.liftingTheTimeoutForOneTransactionDoesNotOutliveIt`) |

### Accepted risks and next steps

| Item | Decision |
|---|---|
| A 304 still runs the query | Acceptable: the CDN `max-age` is what spares the server; versioned ETags would need a change counter per list |
| No load test in CI | Needs a production-like environment; k6 script in [PERFORMANCE.md](../modules/PERFORMANCE.md) |
| Virtual threads not enabled | ADR-016: revisit with slow remote calls or Java 25 |
| `QueryCountIT` counts Hibernate statements only | JdbcTemplate is used by jobs and the outbox, not by these endpoints |
