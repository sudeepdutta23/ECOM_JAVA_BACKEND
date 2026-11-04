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
    config/          OpenAPI, Clock, JPA auditing, async (MDC propagation)
    exception/       ErrorCode, BusinessException, ProblemDetails, GlobalExceptionHandler
    mail/            EmailSender abstraction, email outbox and its relay
    persistence/     BaseEntity, UUID v7 generator
    response/        ApiResponse envelope, FieldErrorDetail
    security/        SecurityConfig, CORS, headers, 401/403 handlers, rate limiting
    storage/         FileStorage port and its local implementation (Phase 8, docs/modules/FILES.md)
    cache/           JsonCache: read-through cache in Redis for public data (Phase 11, docs/modules/REDIS.md)
    util/            UuidV7, Masking
    web/             RequestIdFilter
  system/            connectivity endpoint
  auth/              Phase 1 (complete): see docs/modules/AUTH.md
  rbac/              Phase 2: roles, permissions, method security; see docs/modules/RBAC.md
  (user administration lives in auth/, which owns the User entity: see docs/modules/USERS.md)
  org/               Phase 4: marketplace sellers, members, organisation roles; see docs/modules/ORGANIZATIONS.md
  profile/           Phase 5: names, phone, language, time zone, address book; see docs/modules/PROFILE.md
  catalog/           Phase 6: categories, seller products, stock, storefront; see docs/modules/CATALOG.md
  order/             Phase 6: cart, checkout, orders; see docs/modules/ORDERS.md
  notification/      Phase 7: in-app inbox, order emails, preferences; see docs/modules/NOTIFICATIONS.md
  audit/             Phase 9: append-only audit log written by @Audited methods; see docs/modules/AUDIT.md
  ...
```

### Module dependencies

```
           catalog ──┐
                     v
auth ──> org ──> rbac ──> common
  │                ^
  ├──> profile ────┘
  └────────────────┘
```

(Since step 4.5 auth calls `org.service.MemberDeparture` before an account is deactivated or erased, like `rbac.requireCanLeave`. Since step 5.1 it calls `profile.service.ProfileEraser` during erasure, and since 5.3 `PersonalDataExporter` collects the data export from org and profile through their public APIs.)

- `auth` calls `rbac.service.AuthorizationService` to read a user's authorities and to grant the default role.
- `rbac` never imports `auth`. It knows users only by UUID; the database foreign key keeps integrity.
- When rbac needs facts about users (exists? active?), it declares the `rbac.service.UserDirectory` interface and auth implements it. The dependency still points from auth to rbac.
- Before an account stops being active, auth calls `rbac.service.RoleAssignmentService.requireCanLeave`, so the last administrator cannot disappear.
- `org` reuses rbac's `UserDirectory` port for facts about users and never imports `auth`. Organisation authorization is read from memberships per request, not from the token ([ADR-012](adr/ADR-012-tenancy-model.md)).
- `profile` reuses the same port, including `lockIfActive`, which locks the account row while personal data is written so that an erasure cannot be overtaken ([modules/PROFILE.md](modules/PROFILE.md)). It imports neither `auth` nor `org`, and `org` does not import it.
- `order` uses the catalogue (prices, stock reservation), profile (addresses), org (sellers, guard) and rbac's `UserDirectory`; only auth may call it (erasure). See [modules/ORDERS.md](modules/ORDERS.md).
- `notification` listens to `order.OrderEvent` and uses org's member list and rbac's `UserDirectory`; no business module depends on it ([modules/NOTIFICATIONS.md](modules/NOTIFICATIONS.md)).
- `audit` depends on nothing but `common` (and rbac's permission names): modules mark actions with `common.audit.@Audited`, and an aspect in `audit` records them. No module may depend on `audit` ([modules/AUDIT.md](modules/AUDIT.md)).
- `catalog` uses org's guard and seller directory. org tells it about seller status changes with an in-transaction event, never a call ([ADR-013](adr/ADR-013-in-transaction-module-events.md)).

### Request pipeline

```
RequestIdFilter -> RateLimitFilter -> Spring Security (CORS, bearer JWT, rules) -> Controller
      MDC id        429 before work     401/403 as Problem Details               -> Service -> Repository
```

Inside a module, sub-packages are created only when needed: `controller`, `service`, `repository`, `entity`, `dto`, `mapper`.

## Dependency rules

1. Every module may depend on `common`. `common` depends on no module.
2. Within a module: `controller -> service -> repository -> entity`. Never the reverse.
3. A module never uses another module's `repository` or `entity`. It calls the other module's public service or listens to its events.
4. Controllers contain no business logic and never return entities. They map to DTOs.
5. Cycles between modules are forbidden. If A and B need each other, extract the shared part or use an event.

Rules 3 and 5 are what make later extraction into microservices possible. `ModuleBoundariesTest` (ArchUnit, since Phase 4) enforces them on every build:

- no cycles between modules; `common` depends on no module; `rbac` knows neither `auth`, `org` nor `profile`; `org` does not know `auth` or `profile`; `profile` does not know `auth` or `org`;
- a module's `entity`, `repository`, `dto` and `controller` packages are private to it: other modules use only its root package (public types such as `rbac.Permission`, `org.OrgPermission`) and its `service` package;
- controllers never use repositories; repositories never call services or controllers.

It found one violation when it was introduced: `PermissionController` read its repository directly. That now goes through `PermissionCatalogService`.

## Cross-cutting concerns

| Concern | Where |
|---|---|
| Correlation ID | `RequestIdFilter`, put into the logging MDC and the `X-Request-Id` header |
| Error format | `GlobalExceptionHandler`, RFC 9457 Problem Details |
| Success format | `ApiResponse` envelope |
| Time | Injected `Clock` bean, always UTC |
| Schema | Flyway migrations; Hibernate only validates |
| Health | Spring Boot Actuator |
| Background jobs | `@Scheduled` + ShedLock (one server per round), small batches, short transactions ([modules/JOBS.md](modules/JOBS.md)) |
| Performance | Batched association loading guarded by statement budgets, HTTP caching with ETags for the storefront, server-side statement and idle-transaction timeouts, platform threads ([modules/PERFORMANCE.md](modules/PERFORMANCE.md), [ADR-016](adr/ADR-016-platform-threads.md)) |
| Observability | Micrometer: Prometheus metrics on a separate management port, OpenTelemetry tracing, request and trace IDs in every log line ([modules/OBSERVABILITY.md](modules/OBSERVABILITY.md)) |
| Durable emails | Transactional outbox in PostgreSQL, relay with `SKIP LOCKED` ([modules/EVENTS.md](modules/EVENTS.md), [ADR-015](adr/ADR-015-email-outbox.md)) |
