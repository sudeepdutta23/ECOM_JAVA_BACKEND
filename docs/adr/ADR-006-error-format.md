# ADR-006: RFC 9457 Problem Details for errors

- Status: Accepted
- Date: 2026-10-26

## Context

Clients need one predictable error format. A custom `{ "success": false, ... }` error envelope was proposed.

## Decision

- Errors use RFC 9457 Problem Details (`application/problem+json`), extended with `code`, `timestamp`, `requestId` and an optional `errors` list.
- Successful responses use the `ApiResponse` envelope with `success`, `message`, `data`, `timestamp`, `requestId`.

## Alternatives considered

| Option | Trade-off |
|---|---|
| Custom error envelope | Matches the success envelope, but every client and gateway must learn a private format |
| Problem Details for errors, bare resources for success | Purest REST, but loses the uniform `requestId` and `message` on success |

## Consequences

- Errors follow an IETF standard that Spring, API gateways and many client libraries understand natively.
- Spring's own errors (404, 405, 415) need only enrichment, not reinvention.
- Clients check the HTTP status first; `success` exists only on 2xx responses.
