# ADR-012: Marketplace tenancy with shared tables and explicit organisation scoping

- Status: Accepted
- Date: 2026-11-02

## Context

The platform becomes a marketplace (Phase 4): sellers are organisations with their own staff, catalogue and orders, while customers are global and buy from many sellers in one cart. A seller's data must never be visible to, or changeable by, another seller. The isolation mechanism chosen now applies to every table Phase 6 adds.

## Decision

1. **The tenant is the organisation (seller).** Customers and platform staff are not tenants.
2. **Shared tables.** One database and one schema; every seller-owned row has `organization_id NOT NULL` with a foreign key.
3. **Explicit scoping in three layers:** organisation-scoped URLs (`/organizations/{orgId}/...`); a guard that checks membership and organisation permission per request; repository methods that always take the organisation ID. Non-members get 404.
4. **Organisation authorization is read from the database per request**, not from the access token. Platform authorization stays in the token (ADR-010).
5. **Fixed organisation roles** (`OWNER`, `MANAGER`, `STAFF`) mapped to permissions in code.

## Alternatives considered

| Option | Why not (now) |
|---|---|
| Schema or database per tenant | Strongest isolation, but thousands of small sellers mean thousands of schemas to migrate, and the customer side (one cart, one search across all sellers) needs cross-tenant queries everywhere |
| PostgreSQL row-level security | Enforced by the database even if code forgets, but needs a per-transaction setting (`SET LOCAL app.org_id`) on pooled connections, and the customer and platform paths would need bypass policies. A good second line of defence; revisit in Phase 18 |
| Hibernate `@TenantId` / filters | Built for one tenant per session (SaaS). In a marketplace one request often spans tenants (a cart, a platform list), so the filter would be switched off exactly where it matters |
| Organisation in a request header (`X-Organization-Id`) | Invisible in URLs and logs, easy to forget in caches and links; the path makes the scope explicit and testable |
| Memberships in the access token | Unbounded size, and a removed member keeps access to seller data until the token expires |

## Consequences

- Isolation depends on discipline in three places, so it is tested mechanically (step 4.4): `TenantIsolationIT` discovers every organisation-scoped endpoint from the OpenAPI spec and calls it as an outsider and with another seller's IDs; `ModuleBoundariesTest` keeps other modules away from org's entities and repositories.
- One indexed membership read per seller request (primary key). Cacheable in Phase 11.
- Cross-seller features (customer search, carts, platform reporting) stay simple SQL over shared tables.
- Moving one very large seller to its own database later is possible, because every row already carries `organization_id`.
