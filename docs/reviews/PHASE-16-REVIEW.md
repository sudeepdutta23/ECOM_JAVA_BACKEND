# Phase 16 review: Docker

- Date: 2026-11-05
- Scope: `Dockerfile`, `.dockerignore`, the Compose `app` service
- Result: complete; the image was built and smoke-tested with both profiles (below)

## 1. Smoke test

| Check | Result |
|---|---|
| Build | Succeeds; layers: dependencies 92.7 MB, loader 0.7 MB, application 2.5 MB (what a code change ships) |
| User | UID/GID 10001; `/app` owned by root, not writable |
| Compose, dev profile | Healthy in about 55 s; ping 200; storefront with `ETag` and `Cache-Control: max-age=300, public`; `/actuator/prometheus` 401 |
| Compose hardening | Root filesystem read-only, the upload volume writable |
| prod profile, alone | Readiness 200 on 8081 in about 70 s; scrape 200 on 8081, 401 on 8080; actuator 404 on 8080; ECS JSON logs; JVM is PID 1 |
| Memory | 1 GB limit gives a 768 MB heap (75 %) |
| `docker stop` | Graceful shutdown (pool closed, context stopped) in 2 s; exit code 143, the JVM's normal answer to SIGTERM |

## 2. Security review

| Risk | Handling |
|---|---|
| Secrets in an image layer | `.dockerignore` allows only build inputs and excludes `.env`, `*.pem`, `*.key`; secrets come from the environment or mounted files |
| Running as root | Fixed non-root UID; code not writable |
| Compiler and build tools in production | Runtime stage is a JRE only |
| Container escape and privilege escalation | Compose shows `cap_drop: ALL`, `no-new-privileges`, read-only root |
| Metrics exposed | Management port 8081 not published; denied on 8080 |
| An unconfigured container running with dev settings | The image defaults to `prod`, which refuses to start without its required settings |

No defect was found.

### Accepted risks and next steps

| Item | Decision |
|---|---|
| Base image tags (`21-jre-alpine`) move | Pin by digest in CI and let a bot (Dependabot, Renovate) propose updates |
| No image vulnerability scan yet | Phase 17: scan in CI (Trivy) and fail on fixable criticals |
| No `HEALTHCHECK` in the image | The port depends on the profile; orchestrators define probes (Compose and DOCKER.md show them) |
| Local file storage in a container | A volume for one instance; S3 behind `FileStorage` for several |
