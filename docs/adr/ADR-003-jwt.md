# ADR-003: JWT access tokens with opaque refresh tokens

- Status: Accepted (detailed design in Phase 1)
- Date: 2026-10-26

## Context

The API must be stateless so any instance can serve any request. Users must be able to log out and have sessions revoked.

## Decision

- **Access token:** short-lived signed JWT (about 15 minutes), validated without a database call.
- **Refresh token:** long random opaque string, stored only as a SHA-256 hash, rotated on every use, revocable.
- Reuse of an already-rotated refresh token revokes the whole token family (theft detection).

## Alternatives considered

| Option | Trade-off |
|---|---|
| Server-side sessions | Simple revocation, but needs sticky sessions or a shared session store |
| Long-lived JWT only | Cannot be revoked before expiry |
| Opaque tokens for everything | Database or cache lookup on every request |

## Consequences

- Fast, horizontally scalable authentication.
- Logout is immediate for refresh tokens; an access token stays valid until it expires (maximum about 15 minutes).
- Signing key management becomes critical.
