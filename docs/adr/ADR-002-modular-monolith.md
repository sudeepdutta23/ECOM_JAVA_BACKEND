# ADR-002: Modular monolith instead of microservices

- Status: Accepted
- Date: 2026-10-26

## Context

One team is building a new product. Domain boundaries are not yet proven by real usage. Microservices add network calls, distributed transactions, service discovery, and per-service deployment pipelines.

## Decision

Build one deployable application split into feature modules with enforced boundaries (see ARCHITECTURE.md dependency rules).

## Alternatives considered

| Option | Why not now |
|---|---|
| Microservices | High operational cost before boundaries are known; distributed data consistency is hard |
| Layered monolith (global controller/service/repository folders) | Boundaries erode quickly; extraction becomes impossible |

## Consequences

- Simple deployment, local transactions, easy refactoring across modules.
- One database; each module owns its own tables and never reads another module's tables directly.
- A module can later be extracted when it needs independent scaling or deployment.
- Discipline is required; Phase 6 adds automated architecture tests.
