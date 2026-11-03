# Architecture Decision Records

An ADR records one significant decision: the context, the choice, and its consequences. ADRs are never deleted. A reversed decision gets a new ADR that supersedes the old one.

| ADR | Title | Status |
|---|---|---|
| [ADR-001](adr/ADR-001-postgresql.md) | PostgreSQL as the primary database | Accepted |
| [ADR-002](adr/ADR-002-modular-monolith.md) | Modular monolith instead of microservices | Accepted |
| [ADR-003](adr/ADR-003-jwt.md) | JWT access tokens with opaque refresh tokens | Accepted |
| [ADR-004](adr/ADR-004-flyway.md) | Flyway for schema migrations | Accepted |
| [ADR-005](adr/ADR-005-uuid.md) | UUID primary keys | Accepted |
| [ADR-006](adr/ADR-006-error-format.md) | RFC 9457 Problem Details for errors | Accepted |
| [ADR-007](adr/ADR-007-password-hashing.md) | Argon2id for password hashing | Accepted |
| [ADR-008](adr/ADR-008-jwt-signing.md) | RS256 asymmetric signing for access tokens | Accepted |
| [ADR-009](adr/ADR-009-refresh-token-storage.md) | Hashed, rotating refresh tokens in an HttpOnly cookie | Accepted |
| [ADR-010](adr/ADR-010-authorization-model.md) | Permission-based RBAC with authorities in the access token | Accepted |
| [ADR-011](adr/ADR-011-pagination.md) | Offset pagination for back-office lists, keyset where data is large or moving | Accepted |
| [ADR-012](adr/ADR-012-tenancy-model.md) | Marketplace tenancy with shared tables and explicit organisation scoping | Accepted |
| [ADR-013](adr/ADR-013-in-transaction-module-events.md) | In-transaction events between modules (seller status to the catalogue) | Accepted |

Template for new ADRs:

```markdown
# ADR-NNN: Title
- Status: Proposed | Accepted | Superseded by ADR-XXX
- Date: YYYY-MM-DD
## Context
## Decision
## Alternatives considered
## Consequences
```
