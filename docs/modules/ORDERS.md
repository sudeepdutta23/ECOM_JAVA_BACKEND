# Orders design (Phase 6)

Status: complete (Phase 6 review: [PHASE-6-REVIEW.md](../reviews/PHASE-6-REVIEW.md)).

## 1. Purpose and placement

The `order` module turns browsing into buying: the cart (6.2), checkout (6.3) and the order life cycle (6.4).

```
auth ──> order ──> catalog ──> org ──> rbac ──> common
           ├──────> profile ─────────────^
           └──────> org (sellers, guard)
```

- `order` uses public APIs only: `catalog.service.ProductCatalog` (current prices, availability, stock reservation and release), `org.service.SellerDirectory` (who is selling; the seller lock), `org.service.OrganizationGuard` (seller order views), `profile.service.ProfileReader` (addresses), `rbac.service.UserDirectory` (the account lock).
- Nothing but auth may know `order` (`ModuleBoundariesTest.nothingButAuthKnowsOrders`); auth calls `OrderEraser` during erasure.

## 2. The cart (6.2)

**One cart per account, no ID in any URL.** `GET /api/v1/cart`, `PUT /api/v1/cart/items/{productId}`, `DELETE /api/v1/cart/items/{productId}`, `DELETE /api/v1/cart`. The account comes from the token, like the profile: nothing to guess. Guest carts live in the client until sign-in.

**Quantities, never prices.** `cart_items` holds `(user_id, product_id, quantity)`. Every view prices the cart at today's prices; checkout compares the total with the one the customer saw.

**Lines that stop being buyable stay**, marked `NOT_ON_SALE` (archived product, or the seller is not selling) or `NOT_ENOUGH_STOCK`, and leave the total. `readyForCheckout` is true only for a non-empty cart without problems. The server never silently drops a line.

**Setting, not adding.** `PUT .../items/{productId} {"quantity": 2}` is one `INSERT ... ON CONFLICT DO UPDATE`: a retry, a double click or two tabs give the same result, and there is never a second line for one product.

**Rules.** Only products on sale can be added (404 otherwise). 1 to 99 units per line, at most 50 lines (`app.order.max-cart-lines`, 409 `ORDER_CART_FULL`; a bound, not an invariant). Adding takes the account lock. Erasure deletes the cart. A view is three queries: lines, products (one batch), sellers (one batch).

**Accepted risk.** Asking for more units and watching `NOT_ENOUGH_STOCK` tells a customer roughly how many are left (up to 99). Every shop leaks this; the storefront itself shows only `inStock`.

## 3. Checkout (6.3)

`POST /api/v1/checkout {shippingAddressId, billingAddressId?, expectedTotal}` with an `Idempotency-Key` header. One transaction: either every order is placed and every unit reserved, or nothing happened.

| Step | What | Refused with |
|---|---|---|
| 1 | Key format; the account is active (FOR SHARE lock) | 400; 401 |
| 2 | Advisory lock on the customer's checkouts; a key already used replays that checkout | 200 `Idempotent-Replayed: true` |
| 3 | The cart is not empty and every line can be bought at current prices | 409 `ORDER_CART_NOT_READY` |
| 4 | The server's total equals `expectedTotal` | 409 `ORDER_TOTAL_CHANGED` (with the new total) |
| 5 | Both addresses are the customer's own | 400, the same as for an unknown ID |
| 6 | Each seller is ACTIVE, under a FOR SHARE lock, in seller-ID order | 409 `ORDER_SELLER_NOT_SELLING` |
| 7 | Each line's units are reserved by one conditional UPDATE, in product-ID order | 409 `ORDER_OUT_OF_STOCK` |
| 8 | Write the checkout and one order per seller, with copies of names, prices, the seller's name and both addresses; empty the cart | 201 + Location |

**Snapshots.** An order copies everything it needs: product name, SKU and unit price into `order_items`, the seller's name, and both addresses as JSON documents (`shipping_address`, `billing_address` JSONB, mapped to the `AddressSnapshot` record). Renaming or repricing a product, editing or deleting an address, renaming or closing the seller: nothing reaches an order already placed. Totals are computed by the server from its own prices, never taken from the client.

**Idempotency.** The client sends a new key per purchase (a UUID works). The same key returns the same checkout, even when retries arrive in parallel: the advisory lock makes the second attempt wait, then find the first by its key (`uk_checkouts_customer_key` backs it up). `CheckoutIT` sends five parallel retries: one checkout, one unit taken.

**One global lock order**, so two checkouts, a checkout and a suspension, or a checkout and a stock adjustment can never deadlock:

```
account (FOR SHARE) -> customer's checkouts (advisory) -> sellers by ID (FOR SHARE) -> products by ID (row locks)
```

**The last unit.** The reservation is `UPDATE products SET stock = stock - :q WHERE id = :id AND status = 'ACTIVE' AND seller_active AND stock >= :q`. Two customers buying the last unit at once both pass step 3, but the second UPDATE waits for the first's row lock, then re-evaluates its WHERE clause (READ COMMITTED), finds 0 and gets `ORDER_OUT_OF_STOCK`. The whole second checkout rolls back. `CheckoutIT.theLastUnitCannotBeSoldTwice` proves it.

**Sellers in checkout.** Only ACTIVE sellers sell: pending sellers build their catalogue but take no orders. The seller lock (`SellerDirectory.lockIfSelling`) makes a suspension wait for checkouts in progress; a checkout that starts after it sees the new status.

**Not modelled yet:** shipping costs, taxes (prices are gross), coupons, multiple currencies. Each would be a line or a field on the order, computed by the server at step 8.

## 4. Order life cycle (6.4)

```
PENDING_PAYMENT --pay--> PAID --ship--> SHIPPED --deliver--> DELIVERED
       └------cancel-------┴---cancel-------> CANCELLED (stock returned, paid orders refunded)
```

Each status has its own timestamp (`paid_at`, `shipped_at`, ...), and CHECK constraints require it.

**Payment.** `POST /api/v1/checkouts/{checkoutId}/pay {paymentToken}`: one charge for all orders of the checkout still waiting for payment.

- **No card data on this server.** The client exchanges the card for a one-time token with the provider's SDK; only the token arrives. PCI DSS scope stays with the provider. Tokens are never logged.
- **`PaymentGateway` port** with `charge` and `refund`. `SimulatedPaymentGateway` approves everything except `tok_decline`, like real test tokens. It exists only where `app.payment.provider=simulated` (dev, test). Production requires `PAYMENT_PROVIDER`; without a real gateway it does not start.
- **Never twice.** The checkout row is locked first, so two "pay" clicks cannot both charge; the charge carries the checkout ID as the provider's idempotency key, so a retry after a timeout cannot charge twice either. Paying a paid checkout returns it.
- **Declined: 402 `ORDER_PAYMENT_DECLINED`**, nothing changes, the customer can retry.
- **Accepted for now:** the simulated charge runs inside the transaction. A real provider is called outside it, and a webhook confirms the payment (Phases 12 and 13): holding row locks during a network call would let a slow provider block checkouts. Unpaid orders are cancelled after `app.order.payment-timeout` (30 minutes) by `UnpaidOrderExpiry`, and their stock goes back on sale ([JOBS.md](JOBS.md)).

**Customers** (`/api/v1/orders`): their list (newest first, summaries only), one order with lines and addresses, and cancel before shipping.

**Sellers** (`/api/v1/organizations/{orgId}/orders`): list (optionally by status, for example `PAID` to ship), one order with the shipping address, ship (with an optional tracking number), mark delivered, cancel (a reason is required; the customer sees it). Reads need membership; changes need the new `ORDER_MANAGE` (owner, manager, staff).

**Fulfilment works while suspended.** Seller order commands use intent READ, not the seller-write lock: a suspension stops new sales, not the delivery of what customers already paid for. A closing seller can still ship or cancel its last orders.

**Cancellation** (`OrderCancellation`, the same for both sides): only PENDING_PAYMENT or PAID; the order row is locked, so a cancellation and a shipment of the same order cannot both succeed (`OrderLifecycleIT.cancelAndShipAtOnceNeverBothSucceed`); the units go back to the catalogue (`ProductCatalog.release`, product-ID order); a paid order is refunded through the gateway.

## 5. Erasure and retention

Erasure deletes the cart. **Orders stay**: tax law requires keeping invoices for years (GDPR article 17(3)(b): processing required by a legal obligation). They keep the customer's ID, which after erasure points to an anonymous account row, and the address snapshots, which are part of the invoice. A retention job that removes order data after the legal period is Phase 12.

## 6. Rules

| # | Rule | Enforced by | Step |
|---|---|---|---|
| 1 | A customer sees and changes only their own cart, checkouts and orders | Account from the token; owner-scoped finders | 6.2 to 6.4 |
| 2 | A seller sees and changes only its own orders | Guard, `findByIdAndOrganizationId`, `TenantIsolationIT` | 6.4 |
| 3 | The client never sets a price or a total; the server's total must equal what the customer saw | `CheckoutService`, `expectedTotal` | 6.3 |
| 4 | An order never changes when products, addresses or sellers change | Snapshots | 6.3 |
| 5 | A unit is sold at most once; stock never goes negative | Conditional UPDATE, CHECK | 6.3 |
| 6 | One purchase per idempotency key, also for parallel retries | Advisory lock, unique key | 6.3 |
| 7 | Nothing is sold by a seller that is not ACTIVE, also during a suspension | `lockIfSelling`, `seller_active` | 6.3 |
| 8 | One charge per checkout; no card data on the server | Checkout lock, provider idempotency, tokens | 6.4 |
| 9 | A cancelled order returns its stock and, if paid, its money | `OrderCancellation` | 6.4 |
| 10 | Orders are kept after erasure (tax law); the cart is not | `OrderEraser` | 6.2, 6.4 |

## 7. API

| Method | Path | Authorised by |
|---|---|---|
| GET, DELETE | `/api/v1/cart` | the account |
| PUT, DELETE | `/api/v1/cart/items/{productId}` | the account |
| POST | `/api/v1/checkout` | the account; `Idempotency-Key` |
| GET | `/api/v1/checkouts/{checkoutId}` | its customer |
| POST | `/api/v1/checkouts/{checkoutId}/pay` | its customer |
| GET | `/api/v1/orders`, `/api/v1/orders/{orderId}` | its customer |
| POST | `/api/v1/orders/{orderId}/cancel` | its customer |
| GET | `/api/v1/organizations/{orgId}/orders[/{orderId}]` | member |
| POST | `/api/v1/organizations/{orgId}/orders/{orderId}/ship`, `/deliver`, `/cancel` | `ORDER_MANAGE` |

## 8. Data model (V14, V15)

| Table | Key | Notes |
|---|---|---|
| `cart_items` | `(user_id, product_id)` | Quantity 1 to 99, no price |
| `checkouts` | `id`; `uk_checkouts_customer_key (customer_id, idempotency_key)` | Total, currency, payment reference and time (both or neither) |
| `orders` | `id` | Tenant data (`organization_id`, RESTRICT); status with CHECKed timestamps; JSONB address snapshots; seller name; version |
| `order_items` | `(order_id, line_no)` | Product ID, SKU, name and unit price as sold; quantity |

| Index | Serves |
|---|---|
| `idx_orders_customer_id (customer_id, id)` | My orders, newest first |
| `idx_orders_organization_id (organization_id, id)` | The seller's orders, newest first |
| `idx_orders_organization_status (organization_id, status, id)` | The seller's orders by status (for example everything to ship) |
| `idx_orders_checkout_id` | A checkout's orders (payment, replay) |
| `idx_cart_items_product_id`, `idx_order_items_product_id`, `idx_orders_cancelled_by` | Foreign keys without full scans |

`QueryPlanIT` checks nine plans for cart, checkout and orders.
