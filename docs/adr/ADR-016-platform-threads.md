# ADR-016: Platform threads for request handling, virtual threads later

- Status: accepted
- Date: 2026-11-04
- Phase: 15 (performance)

## Context

Java 21 has virtual threads, and Spring Boot switches Tomcat and `@Async` to them with one property (`spring.threads.virtual.enabled=true`). They make a blocked request almost free, so a server can hold many thousands of requests that wait on I/O.

This application waits almost only on PostgreSQL (and briefly on Redis). The database pool has 10 connections per instance (20 in production), and Tomcat already has up to 200 platform threads.

## Options

1. **Virtual threads everywhere.** Unbounded concurrency for waiting work.
2. **Platform threads (the default).** A fixed pool of 200 request threads.

## Decision

Option 2, for now.

- **The pool is the limit, not the threads.** With 10 to 20 connections, at most 10 to 20 requests make progress on the database at once. 200 threads already wait for a connection; thousands of virtual threads would only wait in a longer line.
- **Backpressure.** With a fixed thread pool, excess requests wait in Tomcat's accept queue, cheaply. With virtual threads, each one is accepted, starts its work, then waits up to the 5 s `connection-timeout` and fails: more work done for more errors under overload.
- **Pinning on Java 21.** A virtual thread blocking inside `synchronized` pins its carrier thread (fixed in Java 24 by JEP 491). Libraries still use `synchronized` in places; on 21 that can stall the few carrier threads.

## Consequences

- Throughput per instance is bounded by the pool. To serve more, add instances (stateless, Phase 11) and size the pool against PostgreSQL's `max_connections` (or put PgBouncer in front).
- **Revisit** when either holds: the request path waits on slow remote calls (a real payment provider, external APIs) rather than the database; or the runtime moves to Java 25 (no pinning). Then enable the property, cap concurrency where it calls the database (a semaphore or a bulkhead), and load-test before and after.
