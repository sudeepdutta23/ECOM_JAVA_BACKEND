# ADR-008: RS256 asymmetric signing for access tokens

- Status: Accepted
- Date: 2026-10-27

## Context

Today one application both issues and verifies tokens. If a module is later extracted into its own service, that service must verify tokens without being able to create them.

## Decision

- Sign access tokens with **RS256** (RSA 2048-bit, SHA-256). Every token carries a `kid` header naming the key.
- Use Spring Security's OAuth2 Resource Server support (Nimbus JOSE) to verify, instead of a hand-written JWT filter.
- Production loads the key pair from environment variables or a secret manager. The dev profile generates a temporary key pair at startup, so a restart signs everyone out locally.

## Alternatives considered

| Option | Trade-off |
|---|---|
| HS256 (shared secret) | Simplest, but every verifier holds the secret and could forge tokens |
| ES256 / EdDSA | Smaller, faster signatures; slightly less universal library support. A fine later upgrade |
| jjwt library + custom filter | Popular in tutorials, but re-implements what Spring Security already provides and tests |

## Consequences

- Other services verify with the public key only, and can fetch it from a JWKS endpoint later.
- Key rotation is possible: publish the new key, sign with it, keep the old public key until old tokens expire.
- Slightly more setup than a shared secret.
