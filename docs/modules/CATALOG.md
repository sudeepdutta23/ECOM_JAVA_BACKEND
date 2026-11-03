# Catalogue design (Phase 6)

Status: complete (Phase 6 review: [PHASE-6-REVIEW.md](../reviews/PHASE-6-REVIEW.md)). The cart, checkout and orders are in [ORDERS.md](ORDERS.md).

## 1. Purpose

Sellers (organisations, Phase 4) list products; customers browse them on one shared storefront. The catalogue is the first **tenant data** of the platform: every product belongs to exactly one seller and follows the seller-endpoint checklist of [ORGANIZATIONS.md](ORGANIZATIONS.md#9-tenant-isolation-44).

| Concept | Owned by | Managed by |
|---|---|---|
| Category | the platform | staff with `catalog:manage` |
| Product (name, SKU, price, stock, status) | one seller | members with `CATALOG_MANAGE` (owner, manager, staff) |
| Storefront | public | nobody: a read-only view of what is on sale |

## 2. Placement and dependencies

New module `catalog`:

```
catalog ──> org ──> rbac ──> common
```

- `catalog` uses org's public API only: `OrganizationGuard` (authorization and the seller lock), `OrgPermission.CATALOG_MANAGE`, `SellerDirectory` (names and slugs of active sellers), and the `OrganizationStatusChanged` event.
- `org` never imports `catalog` (`ModuleBoundariesTest.orgDoesNotKnowCatalog`). It tells the catalogue about status changes by publishing an event.

## 3. Products

| Field | Rule |
|---|---|
| `sku` | The seller's article number: 1 to 64 letters, digits, `.`, `_`, `-`; stored upper-case; unique per seller (`uk_products_organization_sku`), so two sellers may both have `MUG-001` |
| `name`, `description` | Trimmed; no control or text-direction characters (the description may have line breaks) |
| `price` | 0.01 to 1 000 000.00, at most two decimal places, never rounded silently. Stored as `price_minor BIGINT` (cents) |
| `currency` | The one platform currency (`app.catalog.currency`, EUR), checked at startup to have two decimal places |
| `stock` | 0 to 10 000 000, changed only by atomic adjustments |
| `categoryId` | Optional; must exist |

**Life cycle.** `DRAFT --publish--> ACTIVE --archive--> ARCHIVED --publish--> ACTIVE`, and `DRAFT --archive--> ARCHIVED`. Command endpoints (`/publish`, `/archive`) instead of a writable status field. Products are never deleted: an order (step 6.3) keeps its own copy of name and price, but its product page must stay resolvable.

**Money.** A `long` of minor units, never `double` (0.1 + 0.2 is not 0.3 in binary floating point). The API shows `19.99` as a JSON number; `MinorUnits` converts with `RoundingMode.UNNECESSARY`, so `0.999` is an error, not `1.00`.

**Stock is a change, not a total.** `POST .../stock {"delta": 25}` runs one statement:

```sql
UPDATE products SET stock = stock + :delta, version = version + 1
WHERE id = :id AND organization_id = :org AND stock + :delta BETWEEN 0 AND 10000000
```

Two people booking in deliveries at once both count; ten parallel removals from a stock of five leave exactly five successes and zero stock (`CatalogIT`). A read-modify-write on the entity would lose updates. The `ck_products_stock_range` CHECK is the last line. Checkout (step 6.3) uses the same pattern.

## 4. The storefront

`GET /api/v1/catalog/products`, `/products/{id}` and `/categories` need no account (`SecurityConfig` permits `GET /api/v1/catalog/**`).

- **What is on sale:** `status = ACTIVE` and the seller is ACTIVE. Anything else (draft, archived, pending, suspended or closed seller) answers 404, like an unknown ID.
- **What customers see:** name, description, price, currency, `inStock`, category and seller (slug, name). Not the exact stock (competitors could track sales with it), not the SKU, not the status.
- **Filters:** `?category={slug}`, `?seller={slug}`; unknown slugs give an empty page. Full-text search and richer filters are Phase 10.
- **Cost per page:** three queries regardless of size: products, their categories, their sellers (`SellerDirectory.activeSellers`, one batch). No N+1.

### Why `products.seller_active` instead of a join with `organizations`

The storefront must filter and page by "seller is active". A SQL join with the org module's table would couple the catalogue to org's schema, and filtering after paging would break page sizes. So the catalogue keeps its own copy, `seller_active`, and org tells it about every change ([ADR-013](../adr/ADR-013-in-transaction-module-events.md)):

1. `OrganizationAdminService` (approve, suspend, reactivate, close) and the owner's close publish `OrganizationStatusChanged` inside their transaction.
2. `SellerStatusListener` (a plain `@EventListener`, so synchronous, in the same transaction) runs one bulk update on the seller's products.
3. Suspending a seller and hiding its products commit together, or neither does.

A product created while the seller is pending starts with `seller_active = false` and goes live with the approval.

## 5. The seller lock (closes a Phase 4 accepted risk)

Phase 4 accepted that the guard checks the organisation's status without locking it, and required Phase 6 to close that gap before anything is sold. `OrganizationGuard.requireForSellerWrite` does: after the membership and permission check, it takes a **FOR SHARE lock on the organisation row** and decides on the locked status.

- A suspension (`FOR UPDATE` on the same row) waits for writes in progress, and its event then hides what they wrote.
- A write that starts after the suspension sees SUSPENDED and gets 409 `ORG_INVALID_STATUS`.
- Several writes of one seller can hold the share lock at once; only status changes wait.

Every catalogue write uses it. `CatalogIT.aSuspensionWaitsForASellerWriteInProgressAndThenHidesWhatItWrote` pauses a write between its insert and its commit and proves it. Writes to the organisation row itself (rename, membership changes) keep using `require(..., WRITE)` under their own `FOR UPDATE` lock: a share lock followed by an update of the same row would deadlock two parallel renames.

## 6. Rules

| # | Rule | Enforced by | Step |
|---|---|---|---|
| 1 | Every product belongs to one seller; another seller's product ID is 404 | `organization_id NOT NULL`, owner-scoped finders, `TenantIsolationIT` | 6.1 |
| 2 | Only members with `CATALOG_MANAGE` change the catalogue; any member reads it, in every status | `OrganizationGuard` | 6.1 |
| 3 | Nothing changes, and nothing goes on sale, while the seller is suspended or closed | `requireForSellerWrite` (locked status), `seller_active` | 6.1 |
| 4 | Customers see only ACTIVE products of ACTIVE sellers, without stock counts | `StorefrontSearch`, `isOnSale`, response shape | 6.1 |
| 5 | Stock never goes negative, and parallel changes never lose each other | Atomic UPDATE, CHECK | 6.1 |
| 6 | Prices are exact | `price_minor`, `MinorUnits` | 6.1 |
| 7 | A category in use cannot be deleted | FK RESTRICT plus a check, 409 `CATALOG_CATEGORY_IN_USE` | 6.1 |

## 7. API

| Method | Path | Authorised by |
|---|---|---|
| GET | `/api/v1/catalog/products?category=&seller=&page=&size=` | public |
| GET | `/api/v1/catalog/products/{productId}` | public |
| GET | `/api/v1/catalog/categories` | public |
| POST, GET | `/api/v1/organizations/{orgId}/products` | `CATALOG_MANAGE`; any member |
| GET, PUT | `/api/v1/organizations/{orgId}/products/{productId}` | any member; `CATALOG_MANAGE` |
| POST | `.../{productId}/publish`, `.../archive`, `.../stock` | `CATALOG_MANAGE` |
| POST | `/api/v1/admin/categories` | `catalog:manage` |
| PUT, DELETE | `/api/v1/admin/categories/{categoryId}` | `catalog:manage` |

## 8. Data model (V13)

| Index | Serves |
|---|---|
| `uk_products_organization_sku (organization_id, sku)` | SKU unique per seller |
| `idx_products_organization_id (organization_id, id)` | The seller's list newest first; storefront by seller; the bulk `seller_active` update |
| `idx_products_storefront (id) WHERE status = 'ACTIVE' AND seller_active` | The storefront, newest first. Partial: only what customers can see |
| `idx_products_category_id (category_id, id)` | Storefront by category; "is this category in use?" |
| `idx_products_created_by` | `ON DELETE SET NULL` without a full scan |
| `uk_categories_slug` | Category by slug |

`products.organization_id` references `organizations` with RESTRICT: the application never deletes organisations (they are closed), and a stray `DELETE` must not take a seller's history with it.

## 8a. Images (Phase 8)

Product images and their upload pipeline are described in [FILES.md](FILES.md).

## 9. Plan

| Step | Content |
|---|---|
| 6.1 | Categories, seller products, stock, storefront, the seller lock (this document) |
| 6.2 | Cart: per customer, many sellers, prices checked at checkout |
| 6.3 | Checkout: one order per seller, address and price snapshots, atomic stock reservation, idempotency |
| 6.4 | Order life cycle: payment (simulated provider), fulfilment by the seller, cancellation with restock, order history |
| 6.5 | Phase 6 review |
