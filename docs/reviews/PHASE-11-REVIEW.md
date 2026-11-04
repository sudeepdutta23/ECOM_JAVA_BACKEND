# Phase 11 review: Redis

- Date: 2026-11-04
- Scope: `BucketStore` (local and Redis), `RateLimitFilter`, `JsonCache` and its two uses, configuration, docker-compose and Testcontainers
- Result: complete; all automated tests passing (564 unit, 381 integration; see [TESTING.md](../TESTING.md))

## 1. Security and correctness review

| Risk | Handling | Evidence |
|---|---|---|
| N servers let N times the rate limit through | One bucket per client and rule in Redis, updated by compare-and-swap | `SharedRateLimitIT.twoServersShareOneBucketPerClient` |
| A Redis outage takes the site down | Rate limiting and cache fail open; readiness ignores Redis; 2 s timeouts | `RateLimitFilterTest.failsOpenWhenTheBucketStoreIsDown`, `JsonCacheTest.redisDownMeansTheDatabaseAnswersNeverAnError` |
| During a Redis outage, brute force is not rate limited | Per-account lockout and bounded hashing stay (Phase 1); outage logged at ERROR | Accepted; alerting in Phase 14 |
| A cached page outlives a change | Eviction after commit; TTLs of 30 s and 10 min; seller status changes evict all its pages | `CatalogCacheIT` |
| Stale stock on a cached page sells what is gone | Checkout re-checks under lock | Phase 6 `CheckoutIT` |
| Personal or per-user data leaks through a shared key | Only public data is cached | Code review: two keys, both public |
| Corrupt or incompatible cached JSON | Treated as a miss and reloaded | `JsonCacheTest.anUnreadableValueIsReloaded` |
| Redis exposed or unauthenticated | Bound to localhost in docker-compose; password required in production | Configuration |

No defect was found.

### Found while running the suite

- The earlier "forked VM terminated" failures were the machine running out of memory (five `hs_err_pid` reports, all "insufficient memory, G1 virtual space"). Test JVMs are now capped at 1 GB (`-Xmx1g`) and crash files are ignored by git.
- Docker Desktop was not running for part of this phase; integration tests need it.

## 2. Performance review

| Path | Before | After |
|---|---|---|
| Category list | One query per request | One Redis read; the database at most every 10 minutes or after a change |
| Product page | Four queries | One Redis read within 30 s |
| Rate-limited request | In-memory bucket | One Redis round trip (sub-millisecond on the same network) |
