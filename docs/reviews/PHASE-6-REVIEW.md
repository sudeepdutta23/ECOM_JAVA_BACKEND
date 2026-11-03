# Phase 6 review: Core business (catalogue, cart, checkout, orders)

- Date: 2026-11-03
- Scope: the `catalog` and `order` modules, the org additions they rely on (`requireForSellerWrite`, `SellerDirectory`, `OrganizationStatusChanged`, `CATALOG_MANAGE`, `ORDER_MANAGE`), migrations V13 to V15
- Result: complete; all automated tests passing (539 unit, 349 integration; see [TESTING.md](../TESTING.md))

## 1. What was delivered

| Capability | Endpoint(s) | Authorised by |
|---|---|---|
| Categories | `GET /catalog/categories`; `POST, PUT, DELETE /admin/categories` | public; `catalog:manage` |
| Seller catalogue with stock | `/organizations/{orgId}/products[...]`, `/publish`, `/archive`, `/stock` | member; `CATALOG_MANAGE` |
| Storefront | `GET /catalog/products[/{id}]` | public |
| Cart | `/cart`, `/cart/items/{productId}` | the account |
| Checkout | `POST /checkout` (Idempotency-Key), `GET /checkouts/{id}` | the account |
| Payment | `POST /checkouts/{id}/pay` | the account; provider token |
| Customer orders | `/orders`, `/orders/{id}`, `/orders/{id}/cancel` | the account |
| Seller orders | `/organizations/{orgId}/orders[...]`, `/ship`, `/deliver`, `/cancel` | member; `ORDER_MANAGE` |

Design: [modules/CATALOG.md](../modules/CATALOG.md), [modules/ORDERS.md](../modules/ORDERS.md), [ADR-013](../adr/ADR-013-in-transaction-module-events.md).

## 2. Security and correctness review

Method: follow the money and the stock. For every path that creates, moves or returns money or units, ask who could abuse it (a customer, a competitor, another seller, a suspended seller, a double click, a flaky network) and what must still hold (no unit sold twice, no charge twice, no price set by the client, no sale after a suspension, every seller's data its own, nothing personal in logs).

### Findings

No defect was found in the review itself. Three issues were found and fixed while the steps were built, and are recorded because the reasoning matters:

| # | Issue | Fix | Proof |
|---|---|---|---|
| 1 | (Phase 4 accepted risk) The guard checked the seller's status without locking it: a product could go live, or an order be placed, just after a suspension | `requireForSellerWrite` (catalogue) and `lockIfSelling` (checkout) take a FOR SHARE lock on the organisation row; suspensions (FOR UPDATE) wait; the in-transaction event takes the products off sale | `CatalogIT.aSuspensionWaitsForASellerWriteInProgressAndThenHidesWhatItWrote`, `CheckoutServiceTest` |
| 2 | (6.1) A storefront filter on "seller is active" would have needed a join into org's tables | `products.seller_active`, kept by `SellerStatusListener` in the status change's transaction (ADR-013) | `CatalogIT.onlyPublishedProductsOfApprovedActiveSellersAreOnSale` |
| 3 | (6.4) A lock query with a join fetch of the order lines would fail on PostgreSQL (FOR UPDATE cannot lock the nullable side of an outer join) | Lock queries without entity graph; lines load after the lock | `OrderLifecycleIT` |

### Threat checklist

| Attack or failure | Blocked by | Evidence |
|---|---|---|
| A customer sets their own price or total | The request carries no price; the server computes totals from its own prices; `expectedTotal` only confirms | `CheckoutIT.aFailedCheckoutChangesNothing` |
| The price changes between looking and buying | 409 `ORDER_TOTAL_CHANGED` with the new total; nothing bought | `CheckoutIT` |
| The last unit is sold twice | Conditional UPDATE under row lock; the loser's whole checkout rolls back | `CheckoutIT.theLastUnitCannotBeSoldTwice` |
| A double click or network retry buys twice | Idempotency-Key, advisory lock per customer, unique key | `CheckoutIT.parallelRetriesWithOneKeyBuyOnce` |
| Two "pay" clicks charge twice | Checkout row lock; provider idempotency key = checkout ID; paid checkouts replay | `OrderLifecycleIT` (paying twice) |
| Card data reaches the server or the logs | Provider tokens only; `PaymentRequest.toString` hides the token | `OrderLifecycleIT`, `MarketplaceJourneyIT` log audits |
| A suspended seller keeps selling | `seller_active`, `lockIfSelling`, `requireForSellerWrite` | `CartIT`, `CheckoutIT`, `CatalogIT`, `MarketplaceJourneyIT` |
| Customers who paid lose their goods when the seller is suspended | Fulfilment allowed in every status | `OrderLifecycleIT.aSuspendedSellerTakesNoNewOrdersButStillShipsPaidOnes` |
| Another seller reads or changes a seller's products or orders | Guard, owner-scoped finders | `TenantIsolationIT` (22 operations, discovered from OpenAPI) |
| A customer reads someone else's cart, checkout or order | Account from the token, owner-scoped finders, 404 like an unknown ID | `CartIT`, `CheckoutIT`, `OrderLifecycleIT` |
| A customer ships to someone else's address | Addresses looked up by ID and owner | `CheckoutIT.theRequestMustNameTheCustomersOwnAddressAndCarryAKey` |
| A cancellation and a shipment of one order both succeed | Order row lock | `OrderLifecycleIT.cancelAndShipAtOnceNeverBothSucceed` |
| Stock lost or invented by cancellations | `release` in the cancelling transaction; capped by the CHECK | `OrderLifecycleIT`, `MarketplaceJourneyIT` |
| Parallel stock changes lose each other | Delta updates in one statement | `CatalogIT.parallelStockChangesAllCountAndStockNeverGoesNegative` |
| Deadlocks between checkouts, suspensions and stock changes | One global lock order: account, checkouts, sellers by ID, products by ID | `CheckoutServiceTest.locksAreTakenInOneGlobalOrderSoCheckoutsCannotDeadlock` |
| Competitors track a seller's sales | No stock counts on the storefront | `CatalogIT` |
| An order changes after the fact | Snapshots of names, prices, seller name, addresses | `CheckoutIT.aCartOfTwoSellersBecomesTwoOrdersWithSnapshotsAndTheStockIsTaken` |
| An erased customer's invoices vanish, or their cart stays | Orders kept (tax law), cart deleted | `CheckoutIT`, `CartIT` |
| Production runs with the payment simulator | No default provider in prod: startup fails without `PAYMENT_PROVIDER` | `application-prod.yml` |

### Accepted risks and recommendations

| Item | Decision |
|---|---|
| The simulated charge runs inside the checkout transaction | A real provider: payment intent, call outside the transaction, webhook confirmation (Phases 12 and 13) |
| Unpaid orders hold their stock indefinitely | A job cancels orders unpaid after N minutes and releases the stock (Phase 12) |
| A cart reveals stock up to 99 units by probing | Common to all shops; the storefront shows only `inStock` |
| No shipping costs, taxes, coupons or returns | Each is an order line or field computed by the server; returns after delivery are a later feature |
| Refunds are not stored as records | With a real provider: a refunds table with the provider's reference |
| Order data retention after the legal period | A retention job (Phase 12) |
| One platform currency | Multi-currency needs prices per currency; `MinorUnits` assumes two decimal places, checked at startup |

## 3. Performance review

| Path | Work | Cost |
|---|---|---|
| Storefront page | Products (partial index), categories, sellers | 3 queries per page, no N+1 |
| Cart view | Lines, products, sellers | 3 queries per cart |
| Checkout | Account and seller share locks, one conditional UPDATE per line, inserts | Locks per account, per seller (shared) and per product row; short transaction |
| Seller status change | One bulk update of `seller_active` by `idx_products_organization_id` | One statement per seller |
| Order lists | One page query each side, summaries without lines | `idx_orders_customer_id`, `idx_orders_organization_*` |
| Order detail | Order with lines by entity graph | One query |

`QueryPlanIT` checks 20 new plans for the catalogue, cart, checkout and orders (68 in all).

## 4. Production quality checklist

| Question | Answer |
|---|---|
| Loosely coupled? | Yes: `catalog -> org`; `order -> catalog, profile, org, rbac`; org learns nothing about either (event); `ModuleBoundariesTest` (22 rules) |
| Authorization secure? | Yes, see section 2; isolation tested for every seller endpoint automatically |
| Indexes and constraints correct? | CHECKs on prices, stock, quantities, statuses and their timestamps; partial storefront index; RESTRICT keeps history |
| Queries efficient? | Yes, see section 3 |
| Sufficiently tested? | Unit, web slice, HTTP, five real races (stock, last unit, parallel retries, suspension, cancel vs ship), a full journey with log audit |
| Money correct? | Minor units, no floating point, no silent rounding, server-side totals |
| Understandable? | CATALOG.md, ORDERS.md, ADR-013, Javadoc on each rule and lock, this review |
| APIs consistent? | Same envelope and Problem Details; command endpoints for transitions; idempotency headers; Postman collection checked against OpenAPI |
