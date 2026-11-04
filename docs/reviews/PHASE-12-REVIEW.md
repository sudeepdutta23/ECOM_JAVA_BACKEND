# Phase 12 review: Background jobs

- Date: 2026-11-04
- Scope: `SchedulingConfig` (ShedLock), `UnpaidOrderExpiry`, `TokenCleanup`, `NotificationRetention`, migrations V20 and V21
- Result: complete (see [TESTING.md](../TESTING.md))

## 1. Correctness review

| Risk | Handling | Evidence |
|---|---|---|
| A job runs on every server at once | ShedLock row per job, database time | `JobsIT.aJobRoundRunsOnOneServerOnly` |
| A server dies holding a lock | `lockAtMostFor` frees it | ShedLock behaviour, configuration |
| The job cancels an order the customer is paying | Lock and re-check per order; payment locks the same rows | `JobsIT` (paid orders stay); Phase 6 payment locks |
| A paid order or a fresh one is cancelled | Status and age re-checked under the lock | `JobsIT` |
| Stock lost when orders expire | `OrderCancellation` returns it, as for any cancellation | `JobsIT` (stock back) |
| Long transactions or huge deletes | One order per transaction; deletes of 1 000 rows | Design |
| A refresh token deleted while still useful | Only tokens expired for a day; reuse detection compares live tokens | `JobsIT.spentTokensAreDeletedAndLiveOnesKept` |
| A round scans a growing table | One index per job query | `QueryPlanIT` (3 plans) |
| Jobs firing during tests | `app.jobs.enabled=false`; jobs called directly | Test configuration |

No defect was found.

### Accepted risks and next steps

| Item | Decision |
|---|---|
| Audit log retention | Needs monthly partitions of `audit_events`, then dropping old partitions (the table is append-only) |
| Orphaned files after a crash between commit and delete | A storage listing in `FileStorage`, compared with `product_images` |
| No metrics or alerts for jobs | Phase 14: run counts, durations, failures |
| A customer is emailed about an expired order | Intended: they should know their items were released |

## 2. Performance review

| Job | Cost per round |
|---|---|
| Unpaid orders | One partial-index scan; per expired order one lock, one update per line, one stock update |
| Tokens, notifications | Index range scans, batched deletes |

The lock adds one UPDATE on `shedlock` per round per server.
