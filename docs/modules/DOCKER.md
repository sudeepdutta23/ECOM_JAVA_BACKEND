# Docker (Phase 16)

Status: complete (Phase 16 review: [PHASE-16-REVIEW.md](../reviews/PHASE-16-REVIEW.md)).

## 1. The image

`Dockerfile`, two stages:

| Stage | Base | Does |
|---|---|---|
| build | `eclipse-temurin:21-jdk-alpine` | Resolves dependencies (own layer, cached until `pom.xml` changes), packages the jar, extracts its layers |
| runtime | `eclipse-temurin:21-jre-alpine` | A JRE only: no compiler, no Maven, no sources |

- **Layers** from Spring Boot's jar (`-Djarmode=tools extract --layers`): dependencies, loader, snapshot dependencies, application. A code change rebuilds and ships only the last, small layer.
- **Non-root**: UID/GID 10001, no shell login. The code is owned by root and not writable by the process.
- **PID 1 is the JVM** (exec-form `ENTRYPOINT`): `SIGTERM` reaches it, and `server.shutdown: graceful` finishes in-flight requests.
- **Memory**: `-XX:MaxRAMPercentage=75` sizes the heap to the container's limit; `-XX:+ExitOnOutOfMemoryError` lets the orchestrator replace a broken JVM.
- **Defaults to `prod`**: an image started without configuration refuses to start (no database, no JWT key) instead of running with development settings.
- **No tests in the image build**: CI runs the full suite with Testcontainers first (Phase 17), then builds the image from the same commit.
- **`.dockerignore`** sends only `.mvn/`, `mvnw`, `pom.xml` and `src/main/` to the daemon; `.env` files and keys can never end up in a layer.

## 2. Running it

| Goal | Command |
|---|---|
| Infrastructure only, app from the IDE | `docker compose up -d` |
| Everything in containers | `docker compose --profile app up --build`, then http://localhost:8080 |
| The image alone | `docker build -t ecommerce-backend .` |

The Compose `app` service runs the image with the **dev** profile (simulated payments, Mailpit, a generated JWT key), reaching PostgreSQL, Redis and Mailpit by service name. It also shows the hardening production should copy: `read_only` root filesystem with a `tmpfs` for `/tmp`, `cap_drop: [ALL]`, `no-new-privileges`, a memory limit, a readiness health check, and a named volume for uploaded images.

## 3. Production

The same image, with the `prod` profile (the default) and configuration from the platform's secret store:

| Variable | Notes |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | Required |
| `JWT_PRIVATE_KEY_LOCATION` | Required, e.g. `file:/run/secrets/jwt-private.pem` mounted as a secret |
| `JWT_KEY_ID` | Optional; defaults to the key's thumbprint |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | STARTTLS is required |
| `PAYMENT_PROVIDER` | Required |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Shared rate limits and cache |
| `CORS_ALLOWED_ORIGINS` | The frontend's origin |
| `MANAGEMENT_PORT` | Default 8081: probes and metrics, never public |
| `FILES_LOCAL_DIR` | Default `/var/lib/ecommerce/files`; a persistent volume, or one shared by all instances (S3 behind `FileStorage` for a cluster) |

Probes (on the management port): liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`. Give the container a memory limit; the heap follows it. Logs are JSON on stdout.
