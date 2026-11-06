# Redis: shared rate limits and the read cache (Phase 11)

Status: complete (Phase 11 review: [PHASE-11-REVIEW.md](../reviews/PHASE-11-REVIEW.md)).

## 1. Why Redis, and what it is not

Two things need state shared by every server:

1. **Rate limits.** Since Phase 1 each server counted on its own, so three servers let three times the limit through (a known gap since the Phase 1 review). Now one bucket per client and rule lives in Redis.
2. **A read cache** for public data read on almost every request and changed rarely: the category list and product pages.

**Redis is never a source of truth.** Nothing in it is needed to answer correctly, so it runs without persistence (`--save "" --appendonly no`, `allkeys-lru`, 256 MB in docker-compose). Losing it costs speed and, for a while, rate limiting, never data.

## 2. Shared rate limits

`app.rate-limit.store: redis` (the default) gives `RateLimitFilter` a `RedisBucketStore`; `local` keeps the in-memory `LocalBucketStore` for a single instance. Same rules, same headers, same 429 Problem Details as before.

- **One bucket per client and rule**, key `rate-limit:{rule}|{client address}`, shared by every server. Bucket4j updates it with compare-and-swap, so two servers taking the last token at the same moment cannot both succeed. `SharedRateLimitIT` alternates requests between two stores (two "servers") against one Redis: exactly the limit gets through, not twice it.
- **Keys expire** once the bucket would be full again, so Redis holds only clients active in the last period.
- **The client address** is still `remoteAddr`, made the real client by `server.forward-headers-strategy: native` in production (`ForwardedHeadersIT`); the filter never parses `X-Forwarded-For` itself.
- **Fail open.** When Redis cannot be reached, the request goes through and the failure is logged (`Rate limit store unavailable`). Failing closed would turn a Redis outage into a full outage. During such an outage the per-account login lockout (Phase 1) and the bounded password hashing still protect sign-in.

## 3. The read cache

`common.cache.JsonCache`: a small read-through cache of JSON values with a TTL, used explicitly where it pays off.

| Key | Holds | TTL | Evicted |
|---|---|---|---|
| `cache:categories` | The category list | 10 minutes | After commit of every create, rename and delete |
| `cache:product:{id}` | A storefront product page | 30 seconds | After commit of product edits, publish, archive, image changes, and every status change of its seller |

**Rules.**

- **Public data only.** Keys are shared by all visitors: nothing per user, nothing personal, nothing authorization-dependent is cached.
- **Evict after commit.** Evicting earlier would let a concurrent read put the old value straight back. A read that started before the commit can still store an old value just after the eviction; the TTL bounds how long.
- **Stock is not evicted.** `inStock` on a cached page can lag up to 30 seconds. Checkout re-checks stock and seller under lock (Phase 6), so a stale page can never sell what is gone.
- **Every failure falls back to the database**: Redis down, a timeout (2 s), a value that no longer parses. The cache can make pages faster, never unavailable.
- `app.cache.enabled: false` switches it off; unit tests use `JsonCache.disabled()`.

**Why not Spring's `@Cacheable`?** It needs type information embedded in the stored JSON to read records back (or Java serialization), hides the key and TTL in configuration, and swallows nothing by default. Two cached reads do not justify that; an explicit `get(key, ttl, type, loader)` is easier to reason about.

**What is not cached and why:** search results (endless key combinations, and the database query is indexed), seller and customer data (authorization-dependent), order and payment data (must be current).

## 4. Configuration

| Setting | Default | Notes |
|---|---|---|
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `localhost`, `6379`, none | A password is required in production; Redis is reachable only from the application network |
| `RATE_LIMIT_STORE` | `redis` | `local` for a single instance without Redis |
| `CACHE_ENABLED` | `true` | |
| `spring.data.redis.timeout` | 2 s | A slow Redis must not slow requests down for long |

`docker compose up -d` starts Redis next to PostgreSQL and Mailpit; tests start one with Testcontainers (`TestcontainersConfiguration`).

**Health.** Spring Boot adds Redis to `/actuator/health`. The readiness probe does not include it, so a Redis outage never takes servers out of rotation, consistent with failing open.
