# ADR-007: Argon2id for password hashing

- Status: Accepted
- Date: 2026-10-27

## Context

Passwords must survive a database leak. Users pick low-entropy passwords, so the hash must be slow and memory-hard to make offline guessing expensive, especially on GPUs.

## Decision

- Hash passwords with **Argon2id** using OWASP minimum parameters: 19 MiB memory, 2 iterations, parallelism 1, 16-byte salt, 32-byte hash.
- Wrap it in Spring Security's `DelegatingPasswordEncoder`, so every stored hash carries a prefix such as `{argon2}`. Parameters or algorithms can change later, and old hashes are upgraded on the user's next successful login.

## Alternatives considered

| Option | Trade-off |
|---|---|
| BCrypt | Mature and Spring's default, but not memory-hard and silently truncates input after 72 bytes |
| scrypt | Memory-hard, but Argon2id is the newer standard and OWASP's first choice |
| PBKDF2 | Required only for FIPS environments; weak against GPUs |
| SHA-256 with salt | Far too fast; never acceptable for passwords |

## Consequences

- Adds the BouncyCastle library, which Spring's Argon2 encoder uses.
- Each hash costs about 50 to 100 ms of CPU and 19 MiB of memory. This deliberately limits login throughput per core. Rate limiting stops attackers from using it to exhaust the server.
- Changing parameters later needs no migration thanks to the prefix.
