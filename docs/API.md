# API conventions

## Versioning

All business endpoints live under `/api/v1/...`. A breaking change creates `/api/v2/...` for the affected resource; non-breaking additions stay in v1.

## Success responses

```json
{
  "success": true,
  "message": "Service is running",
  "data": { "status": "UP", "service": "ecommerce-backend", "serverTime": "2026-10-26T10:15:30Z" },
  "timestamp": "2026-10-26T10:15:30.123Z",
  "requestId": "9f1c2a4e-1b7d-4c55-9a0e-3f2b8c6d7e81"
}
```

## Error responses (RFC 9457 Problem Details)

`Content-Type: application/problem+json`

```json
{
  "type": "urn:problem-type:validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid",
  "instance": "/api/v1/auth/register",
  "code": "VALIDATION_FAILED",
  "timestamp": "2026-10-26T10:15:30.123Z",
  "requestId": "9f1c2a4e-1b7d-4c55-9a0e-3f2b8c6d7e81",
  "errors": [ { "field": "email", "message": "must be a well-formed email address" } ]
}
```

Clients branch on `code`, never on `detail` or `title`.

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation failed, in the body or in query and path parameters (`errors[].field` names the parameter) |
| 400 | `MALFORMED_REQUEST` | Unparseable JSON, unknown field, wrong type |
| 401 | `UNAUTHORIZED` | Missing or invalid credentials (Phase 1) |
| 403 | `FORBIDDEN` | Authenticated but not allowed (Phase 2) |
| 404 | `RESOURCE_NOT_FOUND` | Unknown route or resource |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method |
| 409 | `DUPLICATE_RESOURCE` | Uniqueness rule checked by a service |
| 409 | `DATA_INTEGRITY_VIOLATION` | Database constraint caught a race |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Wrong Content-Type |
| 422 | `BUSINESS_RULE_VIOLATION` | Valid input that breaks a business rule |
| 400 | `AUTH_INVALID_ACCOUNT_TOKEN` | Email link unknown, expired, used, or for another purpose |
| 401 | `AUTH_TOKEN_EXPIRED` | Access token expired; call refresh |
| 401 | `AUTH_INVALID_CREDENTIALS` | Unknown email, wrong password, or temporarily locked account |
| 403 | `AUTH_EMAIL_NOT_VERIFIED` | Correct password, email not yet verified |
| 403 | `AUTH_ACCOUNT_DISABLED` | Correct password, account deactivated or suspended (the internal reason is never returned) |
| 401 | `AUTH_INVALID_REFRESH_TOKEN` | Refresh cookie missing, unknown, expired, revoked or reused; sign in again |
| 400 | `AUTH_INVALID_CURRENT_PASSWORD` | Wrong current password on change or deactivate (counts toward lockout) |
| 409 | `CONCURRENT_MODIFICATION` | Stale `version`, or another write won the race; reload and retry |
| 403 | `RBAC_ESCALATION_DENIED` | The role, or the account you act on, has permissions you do not hold |
| 409 | `RBAC_SYSTEM_ROLE` | System roles are read-only |
| 409 | `RBAC_ROLE_IN_USE` | Role still assigned; revoke it first |
| 403 | `RBAC_SELF_MODIFICATION_DENIED` | You tried to grant or revoke one of your own roles |
| 409 | `RBAC_LAST_ADMIN` | Revoking, deactivating or suspending would leave no active administrator |
| 403 | `USER_SELF_ACTION_DENIED` | Admin account actions on your own account; use your own account endpoints |
| 409 | `USER_INVALID_STATUS` | The action does not fit the account's status (for example suspending a suspended account) |
| 409 | `ORG_SLUG_TAKEN` | Another organisation, open or closed, already has this slug |
| 409 | `ORG_LIMIT_REACHED` | You already own the maximum number of organisations that are not closed (default 3) |
| 409 | `ORG_INVALID_STATUS` | The organisation's status does not allow the change (suspended or closed organisations are read-only) |
| 403 | `ORG_RANK_DENIED` | The member or role is at or above your own (only owners manage owners) |
| 403 | `ORG_SELF_ACTION_DENIED` | Changing or removing your own membership; leave instead |
| 409 | `ORG_LAST_OWNER` | The change would leave an open organisation without an owner, including deactivating or erasing the account of its only owner |
| 409 | `ORG_ALREADY_MEMBER` | The invited or accepting account is already a member |
| 409 | `ORG_INVITATION_LIMIT` | The organisation has the maximum number of pending invitations (50) |
| 400 | `ORG_INVALID_INVITATION` | Unknown, used, revoked or expired link, or one meant for another address |
| 403 | `ORG_CONFLICT_OF_INTEREST` | Platform staff acting on an organisation they are a member of |

### Client guidance for refresh

- Refresh "single-flight": if several requests get `AUTH_TOKEN_EXPIRED` at once, run ONE refresh and let the others wait for it. Two parallel refreshes with the same cookie look like token theft and end the session.
- On `AUTH_INVALID_REFRESH_TOKEN`, send the user to the login page.
| 429 | `TOO_MANY_REQUESTS` | Rate limit (Phase 1) |
| 500 | `INTERNAL_ERROR` | Anything unexpected; details only in logs |

## Pagination

List endpoints take `page` (from 0) and `size` (default 20, maximum 100) and return, inside `data`:

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 137, "totalPages": 7 }
```

The order is fixed per endpoint and documented with it. A page past the end is empty. See ADR-011.

## Request ID

Send `X-Request-Id` (8 to 64 characters, letters, digits, `.`, `_`, `-`) to correlate calls. Otherwise the server generates one. It is always returned in the response header and body.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/api/v1/system/ping` | Public | Connectivity check |
| POST | `/api/v1/auth/register` | Public | Create account; always 202 |
| POST | `/api/v1/auth/email/verify` | Public | Activate account with emailed token |
| POST | `/api/v1/auth/email/resend-verification` | Public | New verification link; always 202 |
| POST | `/api/v1/auth/login` | Public | Access token in body, refresh token in HttpOnly cookie |
| POST | `/api/v1/auth/refresh` | Refresh cookie | Rotate refresh token, new access token |
| POST | `/api/v1/auth/logout` | Refresh cookie (optional) | End this session, delete cookie; always 204 |
| POST | `/api/v1/auth/logout-all` | Bearer | End every session of the user; always 204 |
| POST | `/api/v1/auth/password/change` | Bearer | Needs current password; ends other sessions, returns a new one |
| POST | `/api/v1/auth/password/forgot` | Public | Email a 30-minute reset link; always 202 |
| POST | `/api/v1/auth/password/reset` | Public | New password with reset token; ends all sessions; 204 |
| GET | `/api/v1/auth/me` | Bearer | Current account summary |
| GET | `/api/v1/auth/me/sessions` | Bearer | My signed-in devices; `current` marks this one |
| DELETE | `/api/v1/auth/me/sessions/{sessionId}` | Bearer | Sign out one device; 204; 404 if not mine |
| POST | `/api/v1/auth/me/deactivate` | Bearer | Needs current password; ends all sessions; 204; 409 `RBAC_LAST_ADMIN` for the last active admin |
| POST | `/api/v1/auth/me/erase` | Bearer | Irreversible. Needs current password; anonymises the account, deletes sessions, links, roles, memberships, the profile and the address book; 204; 409 `RBAC_LAST_ADMIN`, `ORG_LAST_OWNER` |
| GET | `/api/v1/profile` | Bearer | My profile; absent fields were never given; no `version` before the first save |
| PUT | `/api/v1/profile` | Bearer, active account | Replace `{givenName, familyName, phoneNumber, preferredLocale, timeZone, version}`; omitted fields are cleared; no `version` on the first save; 409 `CONCURRENT_MODIFICATION` |
| GET | `/api/v1/auth/me/export` | Bearer | Download my data: account, roles, devices, organisations, profile, addresses; attachment, `no-store`; 5 per 15 min |
| GET | `/api/v1/admin/users/{userId}/profile` | Bearer + `profile:read` | A customer's profile and addresses (support, read only); every view logged; 404 for unknown and erased |
| GET | `/api/v1/catalog/products?category=&seller=&page=&size=` | Public | Products on sale (active sellers only), newest first; `inStock`, no stock counts |
| GET | `/api/v1/catalog/products/{productId}` | Public | One product on sale; 404 for anything else |
| GET | `/api/v1/catalog/categories` | Public | All categories, by name |
| POST | `/api/v1/organizations/{orgId}/products` | Bearer, `CATALOG_MANAGE` | Create a DRAFT `{sku, name, description, price, categoryId}`; 201; 409 `CATALOG_SKU_TAKEN` |
| GET | `/api/v1/organizations/{orgId}/products?status=&page=&size=` | Bearer, member | The shop's products with stock |
| GET | `/api/v1/organizations/{orgId}/products/{productId}` | Bearer, member | One product |
| PUT | `/api/v1/organizations/{orgId}/products/{productId}` | Bearer, `CATALOG_MANAGE` | Replace; `version` required |
| POST | `/api/v1/organizations/{orgId}/products/{productId}/publish` | Bearer, `CATALOG_MANAGE` | On sale; 409 `CATALOG_INVALID_STATUS` if already |
| POST | `/api/v1/organizations/{orgId}/products/{productId}/archive` | Bearer, `CATALOG_MANAGE` | Off sale |
| POST | `/api/v1/organizations/{orgId}/products/{productId}/stock` | Bearer, `CATALOG_MANAGE` | `{delta}`; atomic; 409 `CATALOG_STOCK_INSUFFICIENT` below zero |
| POST | `/api/v1/admin/categories` | Bearer + `catalog:manage` | Create `{slug, name}`; 409 `CATALOG_CATEGORY_SLUG_TAKEN` |
| PUT | `/api/v1/admin/categories/{categoryId}` | Bearer + `catalog:manage` | Rename `{name, version}` |
| DELETE | `/api/v1/admin/categories/{categoryId}` | Bearer + `catalog:manage` | 204; 409 `CATALOG_CATEGORY_IN_USE` |
| GET | `/api/v1/cart` | Bearer | My cart at current prices; problem per line; `readyForCheckout` |
| PUT | `/api/v1/cart/items/{productId}` | Bearer, active account | Set `{quantity}` 1 to 99; 404 if not on sale; 409 `ORDER_CART_FULL` |
| DELETE | `/api/v1/cart/items/{productId}` | Bearer | Remove a line; idempotent |
| DELETE | `/api/v1/cart` | Bearer | Empty; 204 |
| POST | `/api/v1/checkout` | Bearer, `Idempotency-Key` | Buy the cart `{shippingAddressId, billingAddressId?, expectedTotal}`: one order per seller; 201; replay 200 `Idempotent-Replayed`; 409 `ORDER_CART_NOT_READY`, `ORDER_TOTAL_CHANGED`, `ORDER_OUT_OF_STOCK`, `ORDER_SELLER_NOT_SELLING` |
| GET | `/api/v1/checkouts/{checkoutId}` | Bearer, its customer | A checkout with its orders |
| POST | `/api/v1/checkouts/{checkoutId}/pay` | Bearer, its customer | `{paymentToken}` (provider token, never card data); idempotent; 402 `ORDER_PAYMENT_DECLINED` |
| GET | `/api/v1/orders?page=&size=` | Bearer | My orders, newest first |
| GET | `/api/v1/orders/{orderId}` | Bearer, its customer | With lines and address snapshots |
| POST | `/api/v1/orders/{orderId}/cancel` | Bearer, its customer | `{reason?}`; before shipping; stock back, refund if paid |
| GET | `/api/v1/organizations/{orgId}/orders?status=&page=&size=` | Bearer, member | The shop's orders |
| GET | `/api/v1/organizations/{orgId}/orders/{orderId}` | Bearer, member | With the shipping address |
| POST | `/api/v1/organizations/{orgId}/orders/{orderId}/ship` | Bearer, `ORDER_MANAGE` | `{trackingNumber?}`; PAID only; works while suspended |
| POST | `/api/v1/organizations/{orgId}/orders/{orderId}/deliver` | Bearer, `ORDER_MANAGE` | SHIPPED only |
| POST | `/api/v1/organizations/{orgId}/orders/{orderId}/cancel` | Bearer, `ORDER_MANAGE` | `{reason}` required; stock back, refund if paid |
| GET | `/api/v1/profile/addresses` | Bearer | My addresses, newest first (at most 20, no paging) |
| POST | `/api/v1/profile/addresses` | Bearer, active account | Save an address; the first becomes the default for both; 201 + Location; 400 country rules; 409 `PROFILE_ADDRESS_LIMIT` |
| GET | `/api/v1/profile/addresses/{addressId}` | Bearer, owner | One address; 404 if not mine |
| PUT | `/api/v1/profile/addresses/{addressId}` | Bearer, owner | Replace, default flags included; `version` required; 409 `CONCURRENT_MODIFICATION` |
| DELETE | `/api/v1/profile/addresses/{addressId}` | Bearer, owner | 204; a deleted default leaves no default |
| GET | `/api/v1/admin/permissions` | Bearer + `role:read` | Permission catalogue |
| GET | `/api/v1/admin/roles` | Bearer + `role:read` | Roles with permissions |
| GET | `/api/v1/admin/roles/{roleId}` | Bearer + `role:read` | One role |
| POST | `/api/v1/admin/roles` | Bearer + `role:write` | Create custom role; 201 + Location |
| PUT | `/api/v1/admin/roles/{roleId}` | Bearer + `role:write` | Replace name, description, permissions; send `version` |
| DELETE | `/api/v1/admin/roles/{roleId}` | Bearer + `role:write` | Delete unassigned custom role; 204 |
| GET | `/api/v1/admin/users/{userId}/roles` | Bearer + `role:read` | A user's roles, with who granted them and when |
| PUT | `/api/v1/admin/users/{userId}/roles/{roleId}` | Bearer + `role:assign` | Grant; idempotent; no body; 204 |
| DELETE | `/api/v1/admin/users/{userId}/roles/{roleId}` | Bearer + `role:assign` | Revoke; idempotent; 204 |
| GET | `/api/v1/admin/users?status=&email=&page=&size=` | Bearer + `user:read` | Accounts, newest first; email is a case-insensitive prefix; size 1 to 100 |
| GET | `/api/v1/admin/users/{userId}` | Bearer + `user:read` | One account: status change, lockout, active sessions, roles |
| POST | `/api/v1/admin/users/{userId}/suspend` | Bearer + `user:manage` | `{reason}`; ACTIVE only; ends all sessions; returns the account |
| POST | `/api/v1/admin/users/{userId}/reactivate` | Bearer + `user:manage` | SUSPENDED or DEACTIVATED only; returns the account |
| POST | `/api/v1/admin/users/{userId}/unlock` | Bearer + `user:manage` | Ends a failed-login lockout; idempotent |
| POST | `/api/v1/admin/users/{userId}/sign-out` | Bearer + `user:manage` | Ends every session; idempotent |
| POST | `/api/v1/admin/users/{userId}/erase` | Bearer + `user:erase` | Irreversible anonymisation in any status; 409 `USER_INVALID_STATUS` if already erased, `RBAC_LAST_ADMIN` |
| POST | `/api/v1/organizations` | Bearer | Create a seller organisation `{slug, name}`; you become its owner; starts `PENDING_APPROVAL`; 201 + Location |
| GET | `/api/v1/organizations?page=&size=` | Bearer | My organisations with my role, newest first |
| GET | `/api/v1/organizations/{orgId}` | Bearer, member | Organisation with `myRole` and `myPermissions`; 404 for non-members |
| PUT | `/api/v1/organizations/{orgId}` | Bearer, `ORGANIZATION_UPDATE` | Rename `{name, version}`; the slug is immutable |
| GET | `/api/v1/organizations/{orgId}/members` | Bearer, member | Members with email and role |
| PUT | `/api/v1/organizations/{orgId}/members/{userId}` | Bearer, `MEMBER_MANAGE` + rank | Change role `{role}` |
| DELETE | `/api/v1/organizations/{orgId}/members/{userId}` | Bearer, `MEMBER_MANAGE` + rank | Remove; 204 |
| POST | `/api/v1/organizations/{orgId}/leave` | Bearer, member | Leave; 204; 409 `ORG_LAST_OWNER` |
| POST | `/api/v1/organizations/{orgId}/invitations` | Bearer, `MEMBER_MANAGE` + rank | Invite `{email, role}`; 201 |
| GET | `/api/v1/organizations/{orgId}/invitations` | Bearer, `MEMBER_MANAGE` | Pending invitations |
| DELETE | `/api/v1/organizations/{orgId}/invitations/{invitationId}` | Bearer, `MEMBER_MANAGE` + rank | Revoke; 204 |
| POST | `/api/v1/organizations/invitations/accept` | Bearer, the invited address | Accept `{token}`; returns the organisation |
| POST | `/api/v1/organizations/{orgId}/close` | Bearer, owner | Close for good; invitations revoked; not while suspended |
| GET | `/api/v1/admin/organizations?status=&page=&size=` | Bearer + `org:read` | Platform list; `PENDING_APPROVAL` is the approval queue |
| GET | `/api/v1/admin/organizations/{organizationId}` | Bearer + `org:read` | Detail with internal reason and member counts |
| POST | `/api/v1/admin/organizations/{organizationId}/approve` | Bearer + `org:manage` | Pending only; 403 `ORG_CONFLICT_OF_INTEREST` for members |
| POST | `/api/v1/admin/organizations/{organizationId}/suspend` | Bearer + `org:manage` | `{reason}`; active only |
| POST | `/api/v1/admin/organizations/{organizationId}/reactivate` | Bearer + `org:manage` | Suspended only |
| POST | `/api/v1/admin/organizations/{organizationId}/close` | Bearer + `org:manage` | Any status except closed |

`GET /api/v1/auth/me` also returns `roles` and `permissions` (read from the database, so current even before a token refresh).
| GET | `/actuator/health` | Public | Health, including database |
| GET | `/actuator/info` | Public | Build information |
