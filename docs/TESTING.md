# Testing

## Layers

| Kind | Naming | Runs in | Needs Docker | Tools |
|---|---|---|---|---|
| Unit | `*Test` | `mvn test` | no | JUnit 5, Mockito, AssertJ |
| Web slice | `*Test` with `@WebMvcTest` | `mvn test` | no | MockMvc |
| Integration | `*IT` | `mvn verify` | yes | `@SpringBootTest`, Testcontainers PostgreSQL |

Surefire runs `*Test`. Failsafe runs `*IT`.

`pom.xml` pins Testcontainers to 1.21.4. The 1.21.3 release managed by Spring Boot 3.5.7 cannot talk to Docker Engine 29 and fails with "Could not find a valid Docker environment" and an HTTP 400. Remove the override once the Spring Boot version manages 1.21.4 or newer.

## Principles

- Integration tests use a real PostgreSQL, never H2, because constraints, types and SQL dialect must match production.
- Time-dependent logic receives a `Clock` so tests use a fixed clock.
- Each test is independent and leaves no shared state.

## Phase 0 tests

| Test | What it proves |
|---|---|
| `RequestIdFilterTest` | IDs are generated, reused when safe, rejected when unsafe, and removed from the MDC |
| `GlobalExceptionHandlerTest` | Every error type maps to the correct status and code without leaking internals |
| `SystemControllerTest` | Ping returns the envelope; a wrong method returns Problem Details |
| `ApplicationIT` | Full app starts on PostgreSQL, Flyway runs, health is UP, OpenAPI is published |
