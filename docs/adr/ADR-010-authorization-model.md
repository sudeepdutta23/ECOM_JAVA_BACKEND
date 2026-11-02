# ADR-010: Permission-based RBAC with authorities embedded in the access token

- Status: Accepted
- Date: 2026-10-30

## Context

Endpoints need to decide who may do what. Administrators must be able to define new roles without a code release. Authorisation must stay fast and stateless (ADR-003).

## Decision

1. **Permissions are code, roles are data.** A `Permission` enum lists every action the code checks; migrations seed matching rows. Roles are rows that bundle permissions, and admins can create them.
2. **Code checks permissions only**, with `@PreAuthorize("hasAuthority('...')")`, never role names.
3. **The access token carries the user's roles and resolved permissions.** They are computed at login and refresh. Requests are authorised from the token without a database call.
4. **Escalation guards:** nobody can grant permissions they do not hold, change their own roles, or remove the last administrator.

## Alternatives considered

| Option | Trade-off |
|---|---|
| Check role names in code (`hasRole('ADMIN')`) | Simple, but every new role needs a code change |
| Roles only in the token, permissions looked up per request | Role edits apply instantly, but every request needs a cache or database lookup |
| Permissions looked up per request from the database | Always current; adds a query to every request |
| Token version column checked per request | Instant revocation; one query per request, losing statelessness |
| ABAC / policy engine (OPA, Cedar) | Very expressive; heavy for current needs |

## Consequences

- Authorisation costs nothing per request.
- **Role changes take effect at the user's next refresh, up to 15 minutes later.** For urgent removal, combine with logout-all for that user.
- Token size grows with permissions. At about 25 bytes each, 50 permissions add roughly 1.3 KB; revisit if a role ever needs hundreds.
- The enum and the database must agree; a startup check enforces it.
