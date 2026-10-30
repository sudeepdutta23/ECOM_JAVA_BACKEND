# ADR-009: Hashed, rotating refresh tokens in an HttpOnly cookie

- Status: Accepted
- Date: 2026-10-27

## Context

Refresh tokens live for days, so stealing one is valuable. They can be stolen from the database, from browser storage through XSS, or in transit.

## Decision

1. Refresh tokens are 256-bit random opaque strings, not JWTs.
2. The database stores only their SHA-256 hash.
3. Each use rotates the token. Presenting an already-rotated token revokes the whole family (all tokens from that login).
4. Browsers receive the token in a cookie: `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`.

## Alternatives considered

| Option | Trade-off |
|---|---|
| Refresh token in the JSON body, stored by the client | Works for any client, but browser storage is readable by any XSS |
| Refresh token as a JWT | Cannot be revoked without a lookup anyway, so self-containment buys nothing |
| Store raw token | A database leak would hand over every session |
| No rotation | A stolen token works silently until it expires |

## Consequences

- A database leak does not expose usable tokens.
- Theft is detected the moment either party uses an old token.
- Cookies bring CSRF considerations; SameSite=Strict, the narrow path and JSON-only endpoints address them.
- Native mobile clients must keep a cookie jar, which standard HTTP libraries provide.
