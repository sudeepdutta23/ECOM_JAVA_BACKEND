# Database

## Engine

PostgreSQL 17, the same image in development (Docker Compose) and tests (Testcontainers). See [ADR-001](adr/ADR-001-postgresql.md).

## Schema management

- Flyway owns the schema. Migrations live in `src/main/resources/db/migration`. See [ADR-004](adr/ADR-004-flyway.md).
- Hibernate runs with `ddl-auto: validate`. It fails startup if entities and tables disagree, and never changes the schema.
- `flyway clean` is disabled in every profile.

## Migration rules

1. File name: `V{n}__{snake_case_description}.sql`, for example `V2__create_auth_tables.sql`.
2. Never edit a migration that has run anywhere. Write a new one.
3. One logical change per migration.
4. Large tables get `CREATE INDEX CONCURRENTLY` in a separate, non-transactional migration.

## Conventions

| Item | Convention | Example |
|---|---|---|
| Tables | plural snake_case | `users`, `refresh_tokens` |
| Primary key | `id UUID` | see [ADR-005](adr/ADR-005-uuid.md) |
| Timestamps | `TIMESTAMPTZ`, stored in UTC | `created_at`, `updated_at` |
| Foreign key | `{entity}_id` | `user_id` |
| Unique constraint | `uk_{table}_{columns}` | `uk_users_email` |
| Index | `idx_{table}_{columns}` | `idx_refresh_tokens_user_id` |
| Foreign key constraint | `fk_{table}_{ref}` | `fk_refresh_tokens_user` |
| Optimistic lock | `version BIGINT` | on mutable aggregates |

## Entity relationships (Phase 1)

```
users 1 ──────< refresh_tokens        (ON DELETE CASCADE)
  │                 └── replaced_by_id ──> refresh_tokens (self, ON DELETE SET NULL)
  └── 1 ──────< account_tokens        (ON DELETE CASCADE)
```

### users

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | no | PK, UUID v7 from the app |
| email | VARCHAR(254) | no | Unique (index with `varchar_pattern_ops` since V4); CHECK trimmed and lower-case |
| password_hash | VARCHAR(255) | no | `{argon2}...` |
| status | VARCHAR(32) | no | PENDING_VERIFICATION, ACTIVE, DEACTIVATED, SUSPENDED (V4), ERASED (V5) |
| email_verified_at | TIMESTAMPTZ | yes | Required when ACTIVE |
| failed_login_count | INTEGER | no | Default 0, never negative |
| locked_until | TIMESTAMPTZ | yes | Temporary lockout end |
| last_login_at | TIMESTAMPTZ | yes | |
| password_changed_at | TIMESTAMPTZ | no | |
| status_reason | VARCHAR(500) | yes | V4. Internal note; required and non-blank when SUSPENDED |
| status_changed_at | TIMESTAMPTZ | yes | V4. Null for statuses set before V4 |
| status_changed_by | UUID | yes | V4. FK users, ON DELETE SET NULL |
| version | BIGINT | no | Optimistic locking |
| created_at, updated_at | TIMESTAMPTZ | no | |

### refresh_tokens

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | no | PK |
| user_id | UUID | no | FK users, cascade |
| family_id | UUID | no | Shared by all rotations of one login |
| token_hash | VARCHAR(64) | no | Unique; lowercase hex SHA-256 |
| issued_at, expires_at | TIMESTAMPTZ | no | CHECK expires after issued |
| revoked_at, revoke_reason | TIMESTAMPTZ, VARCHAR(32) | yes | Both set or both NULL |
| replaced_by_id | UUID | yes | Self FK to the rotated successor |
| created_ip, user_agent | VARCHAR(45), VARCHAR(255) | yes | For "active sessions" views |

### account_tokens

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | no | PK |
| user_id | UUID | no | FK users, cascade |
| purpose | VARCHAR(32) | no | EMAIL_VERIFICATION, PASSWORD_RESET |
| token_hash | VARCHAR(64) | no | Unique; lowercase hex SHA-256 |
| expires_at | TIMESTAMPTZ | no | |
| consumed_at | TIMESTAMPTZ | yes | One-time use marker |
| created_at | TIMESTAMPTZ | no | |

### Indexes and why

| Index | Serves |
|---|---|
| `uk_users_email` | Login and registration lookup by email |
| `uk_refresh_tokens_token_hash` | Refresh and logout: find token by hash |
| `idx_refresh_tokens_user_id` | Logout-all, password change, cascade delete |
| `idx_refresh_tokens_family_id` | Revoke a family on reuse |
| `idx_refresh_tokens_expires_at` | Cleanup job |
| `uk_account_tokens_token_hash` | Verify email, reset password |
| `idx_account_tokens_user_purpose` | Invalidate previous tokens; cascade delete |
| `idx_account_tokens_expires_at` | Cleanup job |

No index on `users.status` alone: a handful of values, so the planner would rarely choose it. V4 adds the composite `(status, id)` instead, which serves "filter by status, newest first" for the admin list (see Phase 3 below).

### Tables considered and deferred

| Table | Decision |
|---|---|
| roles, permissions, user_roles, role_permissions | Phase 2. Phase 1 tokens carry no authorities |
| login_attempts | Not created. Per-account lockout uses two columns on `users`. Per-IP limits live in the rate limiter. Login history goes to `audit_logs` in Phase 9 |
| password_reset_tokens + email_verification_tokens | Merged into `account_tokens`: identical shape, one purpose column |
| audit_logs | Phase 9. Until then, auth events are written as structured log lines |

### Soft delete

`users` has no `deleted_at`. Deactivation and suspension are status changes and keep the row. Erasure for privacy requests (Phase 3, step 3.5) anonymises the row instead of deleting it, so later references such as orders stay valid.

## Entity relationships (Phase 2, RBAC)

```
users 1 ──< user_roles >── 1 roles 1 ──< role_permissions >── 1 permissions
                 └── assigned_by ──> users (nullable)
```

| Table | Primary key | Notable constraints |
|---|---|---|
| permissions | `code` (natural key) | CHECK `resource:action` lower case |
| roles | `id` UUID | `uk_roles_code`; CHECK upper snake case; non-blank name; `version` for optimistic locking |
| role_permissions | (`role_id`, `permission_code`) | Cascade with role; RESTRICT deleting a granted permission |
| user_roles | (`user_id`, `role_id`) | Cascade with user; RESTRICT deleting an assigned role; `assigned_by` SET NULL |

| Index | Serves |
|---|---|
| `pk_role_permissions` | Permissions of a role (leading column) |
| `idx_role_permissions_permission_code` | Roles granting a permission; RESTRICT check |
| `pk_user_roles` | Roles of a user, at every login and refresh |
| `idx_user_roles_role_id` | Holders of a role, e.g. counting admins |
| `idx_user_roles_assigned_by` | Avoids a full scan when a user is deleted (SET NULL) |

V3 is the first migration that changes existing data: it backfills `CUSTOMER` for every existing user. `RbacSchemaIT` replays the upgrade from V2 in a scratch schema to prove it.

## Phase 3 changes (V4, user management)

| Index | Serves |
|---|---|
| `uk_users_email` (rebuilt with `varchar_pattern_ops`) | Login, uniqueness AND admin prefix search (`LIKE 'ali%'`). A default-collation index cannot serve prefix `LIKE`; rebuilding the one index avoids a second index on the same column |
| `idx_users_status_id (status, id)` | Admin list filtered by status, newest first; also the active-admin count, as an index-only scan |
| `idx_users_status_changed_by` | Avoids a full scan when a user is deleted (SET NULL) |

Newest-first ordering uses `id`, not `created_at`: UUID v7 IDs are time-ordered, so the primary key already sorts by creation time.

## Phase 3 changes (V5, erasure)

An erased account keeps its row (later tables reference `users.id`) but no personal data. `ck_users_erased_is_anonymous` makes that a database guarantee: when `status = 'ERASED'`, the email must be `erased-<id>@erased.invalid`, the password hash `{erased}`, and `email_verified_at`, `last_login_at`, `locked_until`, `status_reason` null. Its refresh tokens (IP, user agent), account tokens and role assignments are deleted by the application in the same transaction. No new index: each delete uses an existing index whose leading column is `user_id`.

## Phase 4 changes (V7, organisations)

`organizations` (marketplace sellers) and `organization_members` (who works for which seller, one role each). Seller-owned tables of later phases carry `organization_id NOT NULL` (ADR-012). Full table and index list: [modules/ORGANIZATIONS.md](modules/ORGANIZATIONS.md#11-data-model-v7-v8-v9).

| Index | Serves |
|---|---|
| `uk_organizations_slug` | Storefront lookup; uniqueness |
| `idx_organizations_status_id (status, id)` | Approval queue, newest first |
| `pk_organization_members (organization_id, user_id)` | Membership check per request, member list, owner count |
| `idx_organization_members_user_id` | "My organisations" |
| `idx_organizations_created_by`, `idx_organizations_status_changed_by`, `idx_organization_members_added_by` | `ON DELETE SET NULL` without full scans |

## Phase 5 changes (V10, V11, profiles and addresses)

`user_profiles`: one optional row per account, keyed by `user_id` (FK to `users`, ON DELETE CASCADE). Names, an E.164 phone number, a language tag and an IANA time zone, each NULL when not given and CHECKed for format. Every access is by primary key, so no secondary index. Erasure deletes the row in its own transaction.

`user_addresses` (V11): the address book, UUID v7 key, owner FK with ON DELETE CASCADE, format CHECKs on every field. Orders (Phase 6) copy an address instead of referencing it.

| Index | Serves |
|---|---|
| `idx_user_addresses_user_id (user_id, id)` | My addresses newest first, the 20-address limit, erasure |
| `uk_user_addresses_default_shipping`, `uk_user_addresses_default_billing` | Partial unique `(user_id) WHERE default_*`: one default of each kind per account |

Details: [modules/PROFILE.md](modules/PROFILE.md#8-data-model-v10-v11).

## Phase 6 changes (V13, catalogue)

`categories` (platform-wide, slug unique) and `products` (tenant data: `organization_id NOT NULL`, RESTRICT, so a seller's history cannot be deleted by accident). Money is `price_minor BIGINT` in cents plus `currency`; stock is an `INTEGER` with a CHECK of 0 to 10 000 000, changed only by atomic UPDATEs. `seller_active` is the catalogue's copy of the seller's status ([ADR-013](adr/ADR-013-in-transaction-module-events.md)).

| Index | Serves |
|---|---|
| `uk_products_organization_sku` | SKU unique per seller |
| `idx_products_organization_id (organization_id, id)` | Seller's list, storefront by seller, seller status changes |
| `idx_products_storefront (id) WHERE status = 'ACTIVE' AND seller_active` | Storefront newest first; partial |
| `idx_products_category_id (category_id, id)` | Storefront by category; category in use |

Details: [modules/CATALOG.md](modules/CATALOG.md#8-data-model-v13).

`cart_items` (V14): `(user_id, product_id)` primary key, quantity 1 to 99, no price.

`checkouts`, `orders`, `order_items` (V15): one order per seller per checkout, tenant data with RESTRICT; names, prices, the seller's name and both addresses (JSONB) are copied at checkout; each status has a CHECKed timestamp; `uk_checkouts_customer_key` makes the Idempotency-Key unique per customer. Orders are kept after erasure (tax law). Details: [modules/ORDERS.md](modules/ORDERS.md#8-data-model-v14-v15).

## Phase 7 changes (V16, notifications)

`notifications` (the inbox; internal links only, CHECKed) with `idx_notifications_user_id (user_id, id)` and the partial `idx_notifications_unread (user_id) WHERE read_at IS NULL` for the badge; `notification_preferences` (one optional row per account). Details: [modules/NOTIFICATIONS.md](modules/NOTIFICATIONS.md#7-data-model-v16).

## Phase 8 changes (V17, files)

`product_images`: metadata of re-encoded product images (bytes in `FileStorage`), tenant column, CHECKs on type, size and dimensions, `idx_product_images_product_id (product_id, id)`. Details: [modules/FILES.md](modules/FILES.md).

## Phase 9 changes (V18, audit)

`audit_events`: append-only (a trigger refuses UPDATE, DELETE and TRUNCATE), no foreign keys (entries outlive what they name), IDs only plus the client IP. Indexes by actor, target and organisation, each with `id` for keyset paging. `audit:read` for ADMIN. Details: [modules/AUDIT.md](modules/AUDIT.md).

## Phase 10 changes (V19, search)

`products.search_vector`: a generated `tsvector` (name weight A, description weight B, `simple` configuration), maintained by PostgreSQL itself. `idx_products_search` (GIN) and `idx_products_storefront_price (price_minor, id)`, both partial over what is on sale. Details: [modules/SEARCH.md](modules/SEARCH.md).

## Phase 12 changes (V20, V21, jobs)

`shedlock`: one lock row per scheduled job. V21 adds the `ORDER_EXPIRED` notification type and the indexes the jobs read: `idx_orders_pending_payment (created_at) WHERE status = 'PENDING_PAYMENT'`, `idx_notifications_created_at`, `idx_account_tokens_consumed_at`. Details: [modules/JOBS.md](modules/JOBS.md).

## Migration history

| Version | Description |
|---|---|
| V1 | Baseline, no objects |
| V2 | users, refresh_tokens, account_tokens |
| V3 | permissions, roles, role_permissions, user_roles; seed CUSTOMER and ADMIN; backfill CUSTOMER |
| V4 | `user:read`, `user:manage` for ADMIN; SUSPENDED status with reason, time and actor; admin revoke reasons; list and search indexes |
| V5 | `user:erase` for ADMIN; ERASED status; CHECK that an erased row is anonymous |
| V6 | CHECK: the `@erased.invalid` domain only on erased rows (Phase 3 review) |
| V7 | organizations, organization_members; `org:read`, `org:manage` for ADMIN |
| V8 | organization_invitations: hashed single-use links, one open invitation per address |
| V9 | Index on invitation email, for erasure |
| V10 | user_profiles: names, phone (E.164), language, time zone |
| V11 | user_addresses: address book, one default shipping and one default billing address per account |
| V12 | `profile:read` for ADMIN (support access to personal data) |
| V13 | categories, products; `catalog:manage` for ADMIN |
| V14 | cart_items: one cart per account, quantities only |
| V15 | checkouts, orders, order_items: one order per seller, snapshots, idempotency key, payment reference |
| V16 | notifications, notification_preferences |
| V17 | product_images |
| V18 | audit_events (append-only trigger); `audit:read` for ADMIN |
| V19 | products.search_vector (generated), GIN search index, price index |
| V20 | shedlock (job locks) |
| V21 | `ORDER_EXPIRED` notification type; indexes for the jobs |
