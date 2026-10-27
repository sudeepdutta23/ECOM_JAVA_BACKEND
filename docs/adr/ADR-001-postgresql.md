# ADR-001: PostgreSQL as the primary database

- Status: Accepted
- Date: 2026-10-26

## Context

E-commerce data is highly relational: users, roles, orders, line items, payments, inventory. Money and stock require strict transactional guarantees. The system must run locally for free.

## Decision

Use PostgreSQL 17 for all relational data.

## Alternatives considered

| Option | Why not |
|---|---|
| MySQL | Viable, but weaker on partial indexes, `JSONB`, CTEs and constraint features we will use |
| MongoDB | Multi-document transactions and joins are awkward for orders and payments |
| H2 for tests | Different SQL dialect hides real bugs; Testcontainers gives the real engine |

## Consequences

- Strong ACID transactions, rich constraints, partial and expression indexes, `JSONB` for flexible attributes, full-text search for early search needs.
- Free and available on every cloud as a managed service.
- One more container to run locally; mitigated by Docker Compose.
