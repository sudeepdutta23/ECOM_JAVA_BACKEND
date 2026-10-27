# ecommerce-backend

Enterprise e-commerce backend built as a **modular monolith** with Java 21, Spring Boot 3.5 and PostgreSQL 17.

## Contents

1. [Prerequisites](#prerequisites)
2. [Quick start](#quick-start)
3. [Configuration and profiles](#configuration-and-profiles)
4. [Running tests](#running-tests)
5. [Useful URLs](#useful-urls)
6. [Project layout](#project-layout)
7. [Documentation](#documentation)
8. [Git workflow](#git-workflow)

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 (Temurin recommended) | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Docker Desktop | Recent | `docker info` |
| Git | 2.40+ | `git --version` |

## Quick start

```bash
# 1. Create your local environment file (git-ignored)
cp .env.example .env

# 2. Start PostgreSQL
docker compose up -d
docker compose ps          # wait for "healthy"

# 3. Run the application (dev profile is the default)
mvn spring-boot:run

# 4. Check it works
curl -i http://localhost:8080/api/v1/system/ping
curl http://localhost:8080/actuator/health
```

Stop the database with `docker compose down`. Add `-v` to also delete its data volume.

## Configuration and profiles

| Profile | Used for | Secrets come from |
|---|---|---|
| `dev` | Local development (default) | `.env` file |
| `test` | Automated tests | Testcontainers |
| `prod` | Production | Real environment variables or a secret manager |

Select a profile with `SPRING_PROFILES_ACTIVE=prod`.

Precedence, highest first: command-line arguments, OS environment variables, `application-{profile}.yml`, `application.yml`.

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `DB_HOST` | yes | none | PostgreSQL host |
| `DB_PORT` | yes | none | PostgreSQL port |
| `DB_NAME` | yes | none | Database name |
| `DB_USERNAME` | yes | none | Database user |
| `DB_PASSWORD` | yes | none | Database password |
| `DB_POOL_MAX_SIZE` | no | 10 (prod 20) | Max JDBC connections |
| `SERVER_PORT` | no | 8080 | HTTP port |
| `API_DOCS_ENABLED` | no | false in prod | Expose Swagger in prod |

## Running tests

```bash
mvn test      # unit and slice tests, no Docker needed
mvn verify    # also runs *IT integration tests against PostgreSQL in Docker
```

## Useful URLs

| URL | Purpose |
|---|---|
| http://localhost:8080/swagger-ui.html | Interactive API docs |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |
| http://localhost:8080/actuator/health | Health with DB status |
| http://localhost:8080/actuator/health/liveness | Liveness probe |
| http://localhost:8080/actuator/health/readiness | Readiness probe |
| http://localhost:8080/actuator/info | Build and version info |
| http://localhost:8080/api/v1/system/ping | API connectivity check |

## Project layout

```
src/main/java/com/ecommerce/backend/
  Application.java        entry point
  common/                 shared kernel: config, errors, response envelope, web filters
  system/                 connectivity endpoint
src/main/resources/
  application*.yml        configuration per profile
  db/migration/           Flyway SQL migrations
docs/                     architecture, security, database, ADRs
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [API conventions](docs/API.md)
- [Database](docs/DATABASE.md)
- [Security](docs/SECURITY.md)
- [Testing](docs/TESTING.md)
- [Decisions (ADRs)](docs/DECISIONS.md)

## Git workflow

- `main` is always releasable. Work happens on short-lived branches such as `feature/auth-registration`.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org): `feat(auth): add user registration`.
- Each branch is merged through a pull request after tests pass.
