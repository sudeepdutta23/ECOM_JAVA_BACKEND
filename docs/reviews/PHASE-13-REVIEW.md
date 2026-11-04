# Phase 13 review: Events and the email outbox

- Date: 2026-11-04
- Scope: `EmailOutbox`, `EmailOutboxRelay`, migration V22, `OrderNotifier`, `AuthEmailListener`, `AccountEraser`
- Result: complete (see [TESTING.md](../TESTING.md))

## 1. Correctness review

| Risk | Handling | Evidence |
|---|---|---|
| Email lost by a crash after commit | Row committed with the change; poller sends it | `EmailOutboxIT.aRowClaimedByACrashedServerIsSentAfterItsLease` |
| Email for a rolled-back change | Row inserted in the same transaction | `EmailOutboxIT.aRolledBackTransactionLeavesNoEmail` |
| Two servers send the same email | `FOR UPDATE SKIP LOCKED` and a lease | `EmailOutboxIT.parallelRelaysSendEveryEmailExactlyOnce` |
| Mail outage | Exponential backoff, FAILED after 8 attempts, purge after 7 days | `EmailOutboxIT.aFailedSendIsRetriedLaterAndGivenUpAfterTheLastAttempt`, `EmailOutboxRelayTest` |
| Lease shorter than a slow batch, so a second server resends | Lease 5 min, above 10 x 15 s | `EmailOutboxRelayTest` |
| SMTP holding a database lock | Claim commits first; sending runs outside a transaction | Design |
| Order and security emails still sent | Same tests as before, now through the outbox | `NotificationIT`, `LoginIT`, `SessionIT`, `AccountLifecycleIT` |

## 2. Security and privacy review

| Risk | Handling |
|---|---|
| Raw one-time tokens stored in the database | Verification, reset and invitation emails never use the outbox |
| An erased person's address kept | Erasure deletes waiting rows (`EmailOutboxIT.erasureForgetsWhatIsStillWaitingForTheAddress`); its own confirmation is not queued |
| Addresses or bodies in logs | Logs carry the row ID, the masked address and the subject; `last_error` stores the exception class only |
| Response timing reveals whether an email is registered | The duplicate-registration notice is one insert, the new-account path an insert plus a token: both far below network noise |

No defect was found.

### Accepted risks and next steps

| Item | Decision |
|---|---|
| Duplicate email after a crash between send and delete | At least once is acceptable for notifications |
| One-time link emails can still be lost | The person asks again; storing tokens would be worse |
| No domain-event outbox or broker | Added when a consumer outside the process exists (ADR-015) |
| Backlog and FAILED rows are not alerted on | Phase 14 metrics |

## 3. Performance review

| Path | Cost |
|---|---|
| Checkout payment | One insert per email in the existing transaction |
| Relay claim | One partial-index query and update for 10 rows (`QueryPlanIT`) |
| Poller when idle | One indexed query every 10 s per server |
