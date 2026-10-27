# ADR-004: Flyway for schema migrations

- Status: Accepted
- Date: 2026-10-26

## Context

The schema must be identical and reproducible across developer machines, tests, and production. Hibernate auto-DDL cannot safely alter production tables and leaves no history.

## Decision

All schema changes are versioned SQL files applied by Flyway at startup. Hibernate uses `ddl-auto: validate`.

## Alternatives considered

| Option | Why not |
|---|---|
| Hibernate `update` | Never drops or renames safely, no review, no history |
| Liquibase | Equally capable; XML/YAML changelogs add indirection. Plain SQL is easier to review and learn |
| Manual scripts | Error-prone, no record of what ran where |

## Consequences

- Every change is reviewed in a pull request as plain SQL.
- Startup fails fast if entities and schema disagree.
- Migrations are immutable once applied; mistakes are fixed forward with a new migration.
