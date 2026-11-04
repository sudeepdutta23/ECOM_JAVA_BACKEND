# ADR-013: In-transaction events between modules

- Status: accepted
- Date: 2026-11-03
- Phase: 6 (catalogue)

## Context

The catalogue (Phase 6) depends on org: it needs the guard and seller names. But org also has to tell the catalogue something: when a seller is suspended, its products must leave the storefront at once. If org called the catalogue, the dependency would point both ways (a cycle, forbidden by `ModuleBoundariesTest`).

The storefront must also filter and page by "seller is active" in SQL, without joining the org module's tables.

## Options

1. **A SQL join from the catalogue to `organizations`.** Simple, always current. But it couples the catalogue to org's schema (an invisible dependency the module tests cannot see), and org could not change its table without breaking the catalogue.
2. **Ask org for the active seller IDs and filter in Java.** Breaks paging (pages shrink after filtering) and does not scale with many sellers.
3. **org publishes an event, the catalogue keeps a copy (`products.seller_active`).** The dependency stays `catalog -> org`; the copy is indexed.
   - 3a. **After commit** (`@TransactionalEventListener`, or an outbox): the copy lags, and a crash between the commit and the listener leaves a suspended seller on sale.
   - 3b. **In the same transaction** (`@EventListener`, synchronous): the status change and the copy commit together, or neither does.

## Decision

Option 3b. org publishes `OrganizationStatusChanged` (a record in its public root package) inside each status transition; the catalogue's `SellerStatusListener` updates `seller_active` in the same transaction.

## Consequences

- No window in which a suspended seller is still selling, and no cycle between modules.
- A slow or failing listener slows or fails the status change itself. Acceptable: one indexed bulk update per seller. A listener must never call external systems; those reactions (emails, search index) belong after commit.
- A seller with very many products makes its suspension one larger transaction. If that ever matters, the listener can batch.
- Phase 13 introduced an outbox for emails ([ADR-015](ADR-015-email-outbox.md)). Invariants that must hold at commit, like this one, stay in-transaction.
