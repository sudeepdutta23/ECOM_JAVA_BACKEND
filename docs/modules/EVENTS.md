# Events and the email outbox (Phase 13)

Status: complete (Phase 13 review: [PHASE-13-REVIEW.md](../reviews/PHASE-13-REVIEW.md)). Decision: [ADR-015](../adr/ADR-015-email-outbox.md).

## 1. Three ways to react to a change

| Style | Mechanism | Guarantee | Used for |
|---|---|---|---|
| **In the transaction** | `@EventListener` (synchronous) | Commits with the change, or neither does | `products.seller_active` (ADR-013), the in-app inbox, writing to the outbox |
| **Outbox** | Row in `email_outbox`, relay after commit | Sent at least once if the change committed; never for a rollback | Order emails, security notices |
| **After commit, best effort** | `@Async @TransactionalEventListener(AFTER_COMMIT)` | Never for a rollback; lost on a crash | Emails with one-time links, erasure confirmation |

Rule: whatever must hold at commit is in the transaction; whatever leaves the process goes through the outbox, unless storing it would be a security or privacy problem.

## 2. The events

| Event | Published by | Listeners |
|---|---|---|
| `OrganizationStatusChanged` | org | catalogue (seller_active, cache) |
| `OrderEvent` (Paid, Shipped, Delivered, Cancelled) | order | notification: inbox + outbox |
| `DuplicateRegistrationAttempted`, `AccountLocked`, `RefreshTokenReuseDetected`, `PasswordChanged`, `AccountDeactivated` | auth | auth mail: outbox |
| `VerificationEmailRequested`, `PasswordResetRequested`, `AccountErased` | auth | auth mail: after commit |
| `InvitationCreated` | org | org mail: after commit |

Events are records with IDs and the minimum needed; none carries a password, and token-bearing ones are never logged or stored.

## 3. The outbox

```
business transaction                         after commit
-------------------------------------        ----------------------------------------------
UPDATE orders ... ;                          relay (nudged at once, or the 10 s poller):
INSERT INTO notifications ... ;              1. claim up to 10 due rows (SKIP LOCKED, 5 min lease)
INSERT INTO email_outbox ... ;   COMMIT  ->  2. send each, outside any transaction
                                             3. delete it, or retry later, or mark FAILED
```

- **Claim** in one statement: `UPDATE ... SET attempts = attempts + 1, next_attempt_at = now() + lease WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING ...`. Two servers never get the same row; a row whose server died is claimable again when the lease ends.
- **Retries**: 30 s, 1, 2, 4, 8, 16, 32 minutes, then the 8th attempt marks the row FAILED (error class stored, never the message). FAILED rows are deleted after 7 days.
- **At least once**: a crash between send and delete sends twice.
- **Privacy**: account erasure deletes every waiting row for the address (`EmailOutbox.forget`).

## 4. Configuration

| Setting | Default |
|---|---|
| `app.mail.outbox.poll-interval` | `PT10S` |
| `app.jobs.cleanup-interval` | `PT1H` (purge of FAILED rows) |

In tests, scheduling is off: the after-commit nudge sends, and `EmailOutboxIT` calls the relay directly for retries and crashes.

## 5. Operating it

- `SELECT count(*), min(created_at) FROM email_outbox WHERE status = 'PENDING'`: a growing backlog means the mail server is down or slow.
- `SELECT * FROM email_outbox WHERE status = 'FAILED'`: given up; fix the cause, then `UPDATE ... SET status = 'PENDING', attempts = 0, next_attempt_at = now()` to resend.
- Both are metrics (`emails_outbox_pending`, `emails_outbox_oldest_age_seconds`, `emails_outbox_failed`) with alerts ([OBSERVABILITY.md](OBSERVABILITY.md)).
