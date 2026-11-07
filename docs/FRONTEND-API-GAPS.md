# Frontend integration and functionality gaps

Date: 2026-11-07. Frontend: `Ecommerce-MFEs` (shell, account, catalog, cart, orders, admin, analytics). Backend: this repository.

## 1. Where integration stands

99 of the 100 backend endpoints are called by the frontend. The exception is `GET /system/ping`, a health probe. The typed client is `packages/utils/src/backend-api.ts`, with one function per endpoint.

Before this work the frontend made 4 API calls, all shaped for a different backend. Products were a hard-coded array, and cart, orders and the admin audit log lived in `localStorage`.

**Tested in a real browser (Edge), against a real backend, PostgreSQL and Redis.** The script covers registration and email links, sign-in, profile, addresses, opening a shop, products, stock, images, approval, guest cart and merge, checkout, a declined and an approved payment, shipping, delivery, cancellation with refund, notifications, sessions, token refresh in two tabs, password reset and change, data export, deactivation, erasure, invitations, user and shop administration, roles, categories and the audit log.

Not tested: a real payment provider (none exists), production hosting, mobile layouts, and load.

## 2. Bugs integration exposed

Fixed. All of these were invisible to the backend's own test suite, because no test ran from a browser.

| Where | Bug | Effect |
|---|---|---|
| Backend | `Idempotency-Key` not allowed by CORS | No browser could ever check out |
| Backend | Rate-limit 429 had no CORS headers | A limited user saw "cannot reach the server", and `Retry-After` was hidden |
| Backend | Dev CORS did not include ports 3001-3006 | Microfrontends could not run standalone |
| Frontend | Sent `x-correlation-id`, which CORS refuses | Every API call failed at the preflight |
| Frontend | Scripts in `index.html` used relative paths | Refreshing a nested page, or opening an emailed link, gave a blank page |
| Frontend | Refresh token sent in the body and kept in `localStorage` | Refresh could not work, and the token was readable by any script |
| Frontend | Expected `{ error: { message } }` | Backend errors never reached the user |
| Frontend | Told users 8 characters was enough for a password | The backend requires 12 |
| Both | Browser cache kept the category list for 5 minutes | An admin could not see a category they had just created. Fixed for admin and seller screens only |

## 3. Decisions needed

| # | Gap | Why it matters | Recommendation |
|---|---|---|---|
| 1 | **A page closing during a token refresh signs the user out everywhere.** The server rotates the token, the new cookie is lost, and the next refresh looks like theft. Seen in testing | Random sign-outs and an alarming security email, roughly whenever a reload meets the 15-minute expiry | Accept a just-rotated token for about 10 seconds. It slightly widens what a stolen token can do, so this is a security trade-off |
| 2 | **The refresh cookie is `SameSite=Strict`, and `vercel.json` suggests hosting the frontend on Vercel.** On a different site (`*.vercel.app` and your API) the browser never sends the cookie | Sessions would end every 15 minutes in production | Serve both from one site (`shop.example.com`, `api.example.com`), or proxy `/api` through Vercel. Avoid `SameSite=None` |
| 3 | Two accepted CVEs until 2027-02-01 | See PHASE-17-REVIEW.md | Plan the Spring Boot 4 migration |

## 4. Missing backend functionality

"The UI shows" means the frontend displays it or needs it today, and it is currently static, hidden or removed.

### Must have before real customers

| Gap | The UI shows | Backend needs |
|---|---|---|
| Real payments | Footer shows VISA, Mastercard, UPI, RuPay; checkout has a test-mode panel | A `PaymentGateway` implementation, client token flow, webhooks |
| Tax | "Inclusive of all taxes" shows only beside a discount, so it is hidden today | Tax rules and a tax line on orders |
| Shipping cost | The "FREE" delivery line and "above ₹499" perk were removed: nothing backed them | Shipping rules and a shipping line in the checkout total |
| Returns | The "7-day replacement" promise was removed: nothing backed it | Return requests, statuses, seller approval, partial refunds |
| Currency | The demo data was INR and the backend defaults to EUR | Set `CATALOG_CURRENCY=INR`, or decide on multi-currency |

### Admin console: pages removed because no API exists

| Gap | What existed | Backend needs |
|---|---|---|
| Orders across sellers | Orders page with search, sort and status filters | `GET /admin/orders` |
| Sales figures | Revenue, units, status breakdown, top products | A reporting endpoint with aggregates |
| Product performance | Products page: units, revenue, share | The same reporting endpoint |
| Catalogue moderation | None, but shops are moderated only as a whole | `GET /admin/products`, hide a product |
| CSV export | Orders export | An export endpoint, audited |
| Readable audit entries | Shows actor IDs only | Actor email in the response, for `audit:read` holders |
| Search for shops | Status filter only | Name and slug search on `GET /admin/organizations` |

### Storefront

| Gap | The UI shows | Backend needs |
|---|---|---|
| List price and discounts | Strike-through price, "% OFF", "You save" (hidden when absent) | `listPrice` on products |
| Ratings and reviews | Star rating (hidden when absent) | Reviews tied to delivered orders, an average on products |
| Highlights and specifications | Sections on the product page (hidden when absent) | Structured attributes on products |
| Home page content | Hero slides, deals and category tiles are hard-coded and link to catalogue filters that may not match | Banners, featured products, or a small CMS |
| Category images and hierarchy | Category tiles with images; header pills with dropdown arrows | `imageUrl` and `parentId` on categories |
| Variants | None yet | Size and colour options with separate stock |
| Wishlist | None yet | Saved products per account |
| Coupons | "All Offers" link, "exclusive coupons" text | Promotions and a discount line at checkout |
| Delivery check | "Deliver to 423651" is hard-coded | Serviceable areas and delivery estimates |
| Search suggestions | Header search box | A suggestions endpoint |
| Shop page | "Sold by" is plain text | Public shop profile with description and logo |
| Newsletter | Subscribe form that saves nothing | A subscribe endpoint with consent and unsubscribe |
| Content pages | About, FAQ, Terms and Privacy links go nowhere | Static pages or a CMS (frontend work too) |

### Cart and orders

| Gap | Effect today | Backend needs |
|---|---|---|
| No image on cart lines | The UI keeps a local copy, so another device shows placeholders | `imageUrl` on `CartResponse.Line` |
| No image on order lines | Order pages show text only | An image snapshot on order lines |
| No lines in `OrderSummary` | The order list shows seller and total; showing items needs one request per order | A short line preview in the summary |
| No guest cart or bulk merge | Stored in the browser; merging sends one request per line | `POST /cart/merge`, optional guest carts |
| `PUT` sets absolute quantities | Two tabs adding the same product can lose one | `POST /cart/items/{id}/increment` |
| No invoices | Customers cannot download one | PDF or HTML invoice per order |
| Tracking is one text field | No delivery timeline | Carrier integration or tracking events |
| Refund status not exposed | The UI can only say a refund will happen | `refundStatus` and `refundedAt` on orders |
| Guest order tracking | The "Track your order" link needs sign-in | Lookup by order number and email |
| Order search for sellers | Status filter only | Search by order ID or customer |

### Accounts and notifications

| Gap | Effect today | Backend needs |
|---|---|---|
| No name in `/auth/me` | Sign-in makes a second request to `/profile` | `displayName` in `MeResponse` |
| No live notifications | The badge updates on page load and after an order | SSE or WebSocket, or polling. SSE must first clear the accepted CVE |
| No change of email | Cannot be done | A verified change-of-address flow |
| No two-factor or social sign-in | Not offered | TOTP, OAuth login |
| No avatar | Initials only | Profile image upload |

### Seller tools

| Gap | Backend needs |
|---|---|
| Seller dashboard (sales, low stock) | A per-shop reporting endpoint |
| Product search | `q` on the seller product list, which has only a status filter |
| Bulk product import | A CSV upload endpoint |
| Payouts and commission | A settlement model, once payments are real |

### Analytics microfrontend

It shows only events from the current browser session, and they disappear on reload. Real analytics needs an ingestion endpoint or a third-party tool. It was left as it is.

## 5. Open items in this work

- `ba3b6d0`'s CI run failed in `Verify`. The same application code passed on `7dce15a` and locally. The logs need a GitHub login to read, so the cause is unknown.
- Permissions are enforced by the backend, but the UI does not yet hide what a role cannot do: a staff member sees the controls and gets a clear error.
- The access token stays in `localStorage` so microfrontends can share it, so an XSS flaw could read it for up to 15 minutes. Keeping it in memory would need a shared session module.
