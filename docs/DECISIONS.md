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
