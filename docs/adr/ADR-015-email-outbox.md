# ADR-015: A transactional outbox for emails, not a message broker

- Status: accepted
- Date: 2026-11-04
- Phase: 13 (events)

## Context

Until Phase 12, every email was sent by an `@Async @TransactionalEventListener(AFTER_COMMIT)`. A rolled-back change sent nothing and SMTP never slowed a request, but a crash, a restart or a mail outage between commit and send lost the email for good: a customer who paid got no confirmation, and a person whose password changed was never told.

Emails are the only side effect that leaves the process. Nothing outside the application consumes our events yet; inside it, modules already react to each other with in-process events (ADR-013).

## Options

1. **Keep after-commit sending.** Simple. Loses emails on every crash and outage.
2. **Send inside the transaction.** Nothing is lost, but SMTP latency and outages become checkout latency and checkout failures, and a rollback after the send cannot take the email back.
3. **A message broker (Kafka, RabbitMQ) for events, and a mail consumer.** The standard for many services. But publishing to the broker after commit has exactly the problem of option 1 (the dual write), so it needs an outbox anyway, plus a broker to run, secure and monitor.
4. **A transactional outbox in PostgreSQL.** The email is a row written in the business transaction; a relay sends it after commit and deletes the row.

## Decision

Option 4, for rendered emails (`email_outbox`, `EmailOutbox`, `EmailOutboxRelay`):

- The row carries the finished message, not a domain event: the relay needs no knowledge of modules.
- After commit, the relay is nudged to send at once; a poller every 10 seconds catches retries and crashed servers.
- Claims use `FOR UPDATE SKIP LOCKED` with a lease, so every server sends in parallel without double sends.
- Delivery is at least once, retried with exponential backoff, and marked FAILED after 8 attempts.
- **Excluded on purpose:** emails with one-time links (verification, password reset, invitations), which would store a raw token next to its hash, and the erasure confirmation, which would keep the erased address. Those stay after-commit and best effort; a lost link can be requested again.

## Consequences

- A committed change's email is sent, even across crashes and mail outages of up to about an hour of retries.
- A crash after the send but before the delete sends the email twice. Acceptable for notifications; a provider with idempotency keys could use the row ID.
- The outbox table lives in the busiest database. It stays small (sent rows are deleted) and its claim is one partial-index query.
- When something outside the process needs our events (a search index, analytics, a second service), add an `event_outbox` with typed JSON payloads and a relay publishing to a broker: the same pattern, a different sink. Invariants that must hold at commit stay in-transaction (ADR-013).
