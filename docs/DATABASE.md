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
| email | VARCHAR(254) | no | Unique; CHECK trimmed and lower-case |
| password_hash | VARCHAR(255) | no | `{argon2}...` |
| status | VARCHAR(32) | no | PENDING_VERIFICATION, ACTIVE, DEACTIVATED |
| email_verified_at | TIMESTAMPTZ | yes | Required when ACTIVE |
| failed_login_count | INTEGER | no | Default 0, never negative |
| locked_until | TIMESTAMPTZ | yes | Temporary lockout end |
| last_login_at | TIMESTAMPTZ | yes | |
| password_changed_at | TIMESTAMPTZ | no | |
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

No index on `users.status`: three values, so an index would rarely be chosen by the planner and would slow every write.

### Tables considered and deferred

| Table | Decision |
|---|---|
| roles, permissions, user_roles, role_permissions | Phase 2. Phase 1 tokens carry no authorities |
| login_attempts | Not created. Per-account lockout uses two columns on `users`. Per-IP limits live in the rate limiter. Login history goes to `audit_logs` in Phase 9 |
| password_reset_tokens + email_verification_tokens | Merged into `account_tokens`: identical shape, one purpose column |
| audit_logs | Phase 9. Until then, auth events are written as structured log lines |

### Soft delete

`users` has no `deleted_at`. Deactivation is a status change and keeps the row. Real deletion for privacy requests arrives in Phase 3 and cascades to tokens.

## Migration history

| Version | Description |
|---|---|
| V1 | Baseline, no objects |
| V2 | users, refresh_tokens, account_tokens |
