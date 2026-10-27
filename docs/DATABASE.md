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

## Migration history

| Version | Description |
|---|---|
| V1 | Baseline, no objects |
