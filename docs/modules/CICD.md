# CI/CD (Phase 17)

Status: complete (Phase 17 review: [PHASE-17-REVIEW.md](../reviews/PHASE-17-REVIEW.md)).

## 1. Pipeline

`.github/workflows/ci.yml`, on every push, every pull request to `main`, and by hand:

```
push / PR ──> test ───────────────> image ──────────────────────────────> publish
              ./mvnw verify          build (buildx, layer cache)           ghcr.io/<owner>/<repo>
              unit + integration     scan (Trivy: CRITICAL/HIGH            main and v* tags only
              (Testcontainers)             with a fix fails the build)
              reports on failure
```

| Job | Runs | Fails when |
|---|---|---|
| **test** | `./mvnw -B -ntp verify`: compile, unit tests, integration tests against real PostgreSQL and Redis containers, ArchUnit module rules, query plans and statement budgets | Any test fails; reports are uploaded as an artifact for 7 days |
| **image** | Builds the image from the tested commit, scans it, and on `main` or a `v*` tag pushes it | A fixable critical or high vulnerability |

- **One artifact, promoted.** The image is built once per commit and tagged with the commit SHA (plus `main`, and `1.2.3` / `1.2` for a `v1.2.3` tag). An environment deploys a tag; it never rebuilds from source.
- **Least privilege.** The workflow token is read-only; only the image job may write packages, and it pushes only for pushes to `main` and tags, never for pull requests (which may come from forks).
- **Fast feedback.** Maven dependencies and Docker layers are cached; a newer push cancels the obsolete run of the same branch (never on `main`, where every commit should produce its image).

## 2. Dependencies

`.github/dependabot.yml`: weekly pull requests for Maven (Spring, Micrometer and OpenTelemetry grouped), the Docker base images and the GitHub Actions. Each one runs the full pipeline, so an update merges only when everything still passes.

### Vulnerability exceptions

`.trivyignore.yaml` lists accepted findings, each with its reason and an expiry date: on that date the scan fails again and the decision must be renewed or the dependency upgraded. An exception is only for a finding with no fix on the current release line, in a feature the application does not use, and `NoUnusedVulnerableFeaturesTest` fails the build if that feature is ever used.

Security patches newer than the Spring Boot BOM are version properties at the top of `pom.xml` (`tomcat.version`, `netty.version`, `jackson-bom.version`, `postgresql.version`). Remove each one once the BOM catches up. History: [PHASE-17-REVIEW.md](../reviews/PHASE-17-REVIEW.md).

## 3. Branches and releases

| Event | Result |
|---|---|
| Push to a feature branch | test + image (built and scanned, not published) |
| Pull request to `main` | The same; required to pass before merging (branch protection) |
| Merge to `main` | Published as `sha-<commit>` and `main` |
| Tag `v1.2.3` | Published as `1.2.3` and `1.2` |

Repository settings to apply once (not in code): protect `main` (require the `Build and test` and `Image` checks, require a review, no direct pushes).

## 4. Deployment

Continuous delivery stops at the registry: there is no hosting environment in this project yet. A deployment takes a published tag and runs it with the production settings of [DOCKER.md](DOCKER.md); Flyway migrates the schema at startup, so every migration must stay compatible with the version still running (expand, then contract) for rolling updates. [PRODUCTION-READINESS.md](../PRODUCTION-READINESS.md) lists what a production environment needs.

## 5. Running the same checks locally

| CI step | Locally |
|---|---|
| test | `./mvnw verify` (Docker must be running) |
| image | `docker build -t ecommerce-backend .` |
| scan | `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy image --severity CRITICAL,HIGH --ignore-unfixed ecommerce-backend` |
