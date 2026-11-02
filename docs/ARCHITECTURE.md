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
    mail/            EmailSender abstraction
    persistence/     BaseEntity, UUID v7 generator
    response/        ApiResponse envelope, FieldErrorDetail
    security/        SecurityConfig, CORS, headers, 401/403 handlers, rate limiting
    util/            UuidV7, Masking
    web/             RequestIdFilter
  system/            connectivity endpoint
  auth/              Phase 1 (complete): see docs/modules/AUTH.md
  rbac/              Phase 2: roles, permissions, method security; see docs/modules/RBAC.md
  (user administration lives in auth/, which owns the User entity: see docs/modules/USERS.md)
  org/               Phase 4: marketplace sellers, members, organisation roles; see docs/modules/ORGANIZATIONS.md
  profile/           Phase 5: names, phone, addresses; refers to users by ID
  ...
```

### Module dependencies

```
auth ──> org ──> rbac ──> common
  └───────────────┘
```

(Since step 4.5 auth calls `org.service.MemberDeparture` before an account is deactivated or erased, like `rbac.requireCanLeave`.)

- `auth` calls `rbac.service.AuthorizationService` to read a user's authorities and to grant the default role.
- `rbac` never imports `auth`. It knows users only by UUID; the database foreign key keeps integrity.
- When rbac needs facts about users (exists? active?), it declares the `rbac.service.UserDirectory` interface and auth implements it. The dependency still points from auth to rbac.
- Before an account stops being active, auth calls `rbac.service.RoleAssignmentService.requireCanLeave`, so the last administrator cannot disappear.
- `org` reuses rbac's `UserDirectory` port for facts about users and never imports `auth`. Organisation authorization is read from memberships per request, not from the token ([ADR-012](adr/ADR-012-tenancy-model.md)).

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

- no cycles between modules; `common` depends on no module; `rbac` knows neither `auth` nor `org`; `org` does not know `auth`;
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
