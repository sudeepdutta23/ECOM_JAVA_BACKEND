# Architecture

## Style

A **modular monolith**: one deployable Spring Boot application, internally split into modules with explicit boundaries. See [ADR-002](adr/ADR-002-modular-monolith.md).

```
                    Clients (web, mobile, admin)
                               |
                         HTTPS / JSON
                               |
+--------------------------------------------------------------------+
|                   ecommerce-backend (one JVM)                      |
|                                                                    |
|  Filters: RequestIdFilter -> (Phase 1) Security / JWT -> MVC       |
|                                                                    |
|  +---------+  +---------+  +---------+  +---------+  +---------+   |
|  |  auth   |  |  user   |  |  role   |  | catalog |  |  order  |   |
|  |  api    |  |  api    |  |  api    |  |  ...    |  |  ...    |   |
|  | service |  | service |  | service |  |         |  |         |   |
|  |  repo   |  |  repo   |  |  repo   |  |         |  |         |   |
|  +----+----+  +----+----+  +----+----+  +---------+  +---------+   |
|       |            |            |                                  |
|  +----v------------v------------v---------------------------------+|
|  | common: errors, response envelope, config, web, validation      ||
|  +----------------------------------------------------------------+|
+-------------------------------+------------------------------------+
                                | JDBC (HikariCP)
                       +--------v--------+
                       |  PostgreSQL 17  |   schema owned by Flyway
                       +-----------------+
```

## Package layout

Packages are organised **by feature first, layer second**:

```
com.ecommerce.backend
  common/            shared kernel, no business logic
    config/          OpenAPI, Clock
    exception/       ErrorCode, BusinessException, GlobalExceptionHandler
    response/        ApiResponse envelope, FieldErrorDetail
    web/             RequestIdFilter
  system/            connectivity endpoint
  auth/              Phase 1
  user/              Phase 3
  ...
```

Inside a module, sub-packages are created only when needed: `controller`, `service`, `repository`, `entity`, `dto`, `mapper`.

## Dependency rules

1. Every module may depend on `common`. `common` depends on no module.
2. Within a module: `controller -> service -> repository -> entity`. Never the reverse.
3. A module never uses another module's `repository` or `entity`. It calls the other module's public service or listens to its events.
4. Controllers contain no business logic and never return entities. They map to DTOs.
5. Cycles between modules are forbidden. If A and B need each other, extract the shared part or use an event.

Rules 3 and 5 are what make later extraction into microservices possible. Phase 6 adds an automated architecture test (ArchUnit or Spring Modulith) to enforce them.

## Cross-cutting concerns

| Concern | Where |
|---|---|
| Correlation ID | `RequestIdFilter`, put into the logging MDC and the `X-Request-Id` header |
| Error format | `GlobalExceptionHandler`, RFC 9457 Problem Details |
| Success format | `ApiResponse` envelope |
| Time | Injected `Clock` bean, always UTC |
| Schema | Flyway migrations; Hibernate only validates |
| Health | Spring Boot Actuator |
