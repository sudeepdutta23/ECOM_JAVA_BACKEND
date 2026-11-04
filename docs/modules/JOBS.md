# Background jobs (Phase 12)

Status: complete (Phase 12 review: [PHASE-12-REVIEW.md](../reviews/PHASE-12-REVIEW.md)).

## 1. The jobs

| Job | Module | Every | Does | Closes |
|---|---|---|---|---|
| `UnpaidOrderExpiry` | order | 1 minute | Cancels orders unpaid for `app.order.payment-timeout` (30 min), returns their stock, tells the customer (`ORDER_EXPIRED`) | Phase 6: abandoned checkouts held stock forever |
| `TokenCleanup` | auth | 1 hour | Deletes refresh tokens expired for a day and email links expired or consumed for a day | Spent tokens (with IPs and user agents) growing forever |
| `NotificationRetention` | notification | 1 hour | Deletes notifications older than 90 days | Phase 7: inboxes kept forever |

Not here yet, and why: audit retention needs the log partitioned by month first (dropping a partition, not deleting rows from an append-only table); orphaned files after a crash need a storage listing in `FileStorage`. Both are listed in the review.

## 2. One server per round: ShedLock

A plain `@Scheduled` method runs on every server: three servers would expire every order three times at once. Each job therefore also has `@SchedulerLock(name = ...)`:

- Before a round, the server tries to take the job's row in `shedlock` (V20) by setting `lock_until` in the future. Only one server succeeds; the others skip the round.
- **Database time** (`usingDbTime`): servers with skewed clocks cannot disagree about whether a lock has expired.
- **`lockAtMostFor`** (5 minutes for order expiry, 30 for cleanups): if a server dies mid-round, the lock frees itself and another server takes over at the next round. It is set well above a round's normal duration, so a slow round is never run twice in parallel.
- `JobsIT.aJobRoundRunsOnOneServerOnly` proves it with two executors against the same table.

## 3. How a job is written

- **Small batches**: at most 200 orders, or 1 000 rows per DELETE statement, repeated until a batch comes back short. One huge statement would hold locks, bloat the WAL and lag replicas.
- **Short transactions**: the order job lists due IDs without a lock, then locks, re-checks and cancels **one order per transaction**. A customer paying the same order at that moment either takes the order lock first (the order is PAID and the job skips it) or waits and finds it cancelled. One order per transaction also matters technically: returning stock clears the persistence context.
- **Idempotent**: a round that finds nothing does nothing; a second round never repeats work.
- **An index per job query**, partial where it can be (`idx_orders_pending_payment`): a round costs the same however large the table grows. `QueryPlanIT` checks them.
- **Logged with counts** when a round did something; metrics come with Phase 14.

## 4. Configuration

| Setting | Default |
|---|---|
| `JOBS_ENABLED` (`app.jobs.enabled`) | `true`; `false` stops every job (tests, maintenance) |
| `app.jobs.unpaid-orders-interval` | `PT1M` |
| `app.jobs.cleanup-interval` | `PT1H` |
| `app.order.payment-timeout` | `30m` |
| `app.jobs.notification-retention` | `90d` |

**Tests** run with `app.jobs.enabled=false` and call each job's method directly, with data aged by SQL: deterministic, and no timer firing in the middle of another test.
