# Performance (Phase 15)

Status: complete (Phase 15 review: [PHASE-15-REVIEW.md](../reviews/PHASE-15-REVIEW.md)).

Performance here is mostly about not doing work: no query per row, no scan of a growing table, no request repeated against the server when a cache can answer, and no single query or transaction allowed to hold the pool.

## 1. What guards what

| Concern | Guard | Test |
|---|---|---|
| N+1 queries | Lists load the page, then each association in one batch by ID | `QueryCountIT`: statements equal for 1 row and a full page, under a budget |
| Full scans | An index for every hot query, partial where possible | `QueryPlanIT` (82 plans) |
| Repeated public reads | Redis cache (Phase 11) and HTTP caching with ETags | `CatalogCacheIT` |
| Runaway queries, leaked transactions | `statement_timeout` 15 s, `idle_in_transaction_session_timeout` 60 s | `DatabaseGuardsIT` |
| Deep paging | Offsets capped (10 000 results), keyset paging for the audit log | `SearchIT`, `AuditIT` |
| Slow side effects in requests | Emails after commit through the outbox (Phase 13) | `EmailOutboxIT` |
| Large writes | JDBC batching (`batch_size: 50`, ordered inserts and updates); jobs in batches | Configuration, `JobsIT` |

## 2. Statement budgets

`QueryCountIT` calls each list with `size=1` and with a full page. The count must not change, and must stay within its budget (the whole request, including the caller's account check):

| Endpoint | Budget |
|---|---|
| `GET /api/v1/catalog/products` | 4 |
| `GET /api/v1/orders` | 4 |
| `GET /api/v1/organizations/{id}/orders` | 5 |
| `GET /api/v1/organizations/{id}/products` | 5 |
| `GET /api/v1/notifications` | 3 |
| `GET /api/v1/cart` (1 line or 6) | 4 |

A new association on a listed entity that is read per row fails the first assertion; an extra query fails the budget. Raising a budget is a reviewed decision, not a fix.

## 3. HTTP caching (storefront)

| Endpoint | `Cache-Control` | Why that long |
|---|---|---|
| `/catalog/products`, `/catalog/products/{id}` | `public, max-age=30` | A suspension or price change shows within about a minute |
| `/catalog/categories` | `public, max-age=300` | Changes rarely |
| `/catalog/products/{id}/images/{id}` | `public, max-age=86400` | An image never changes under its ID |

Each JSON response has an `ETag` computed from its `data` (the envelope's timestamp and request ID would make a whole-body hash useless). A client revalidating with `If-None-Match` gets `304 Not Modified` without a body. A 304 still runs the query on the server; the saving is bandwidth and client time, while the CDN's `max-age` saves the server.

Everything personal (cart, orders, profile) keeps Spring Security's `no-store`.

## 4. Database guards

Every pooled connection carries, as PostgreSQL settings (`spring.datasource.hikari.data-source-properties.options`):

- `statement_timeout=15s` (`DB_STATEMENT_TIMEOUT`): a query that runs longer is cancelled by the server, so a bad plan cannot pile up requests behind it.
- `idle_in_transaction_session_timeout=60s` (`DB_IDLE_IN_TRANSACTION_TIMEOUT`): a transaction left open (a bug, a hung remote call) is ended, freeing its connection and its locks.

A migration that needs longer starts with `SET LOCAL statement_timeout = 0`: local to its transaction, so the pooled connection keeps the guard afterwards.

## 5. Threads and pool

Platform threads (Tomcat's 200), 10 connections per instance (20 in production). The pool is the real limit; why virtual threads wait: [ADR-016](../adr/ADR-016-platform-threads.md). Watch `hikaricp_connections_pending` (Phase 14 alert): sustained waiting means more instances, not a bigger pool, until PostgreSQL's `max_connections` is planned for.

## 6. Load testing

Not part of the build (it needs a production-like environment). A starting point with [k6](https://k6.io), against a running instance:

```javascript
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  stages: [{ duration: '1m', target: 50 }, { duration: '3m', target: 50 }, { duration: '30s', target: 0 }],
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<250'] },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const list = http.get(`${BASE}/api/v1/catalog/products?size=20`);
  check(list, { 'list 200': (r) => r.status === 200 });
  const search = http.get(`${BASE}/api/v1/catalog/products?q=mug&size=20`);
  check(search, { 'search 200': (r) => r.status === 200 });
}
```

Run with rate limiting and caches configured as in production, and read the Phase 14 metrics alongside: latency buckets, pool waiting, cache hit ratio.
