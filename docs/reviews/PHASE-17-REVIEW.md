# Phase 17 review: CI/CD

- Date: 2026-11-06
- Scope: `.github/workflows/ci.yml`, `.github/dependabot.yml`, `.trivyignore.yaml`, dependency upgrades, `NoUnusedVulnerableFeaturesTest`
- Result: complete locally; the workflow itself is proven only by its first run on GitHub (see section 3)

## 1. Defects found

| Defect | Effect | Fix |
|---|---|---|
| `mvnw` stored without its executable bit | `./mvnw` fails with "permission denied" on every Linux runner | Mode 100755 in git |
| Scan step named the image by repository, which contains capitals | Invalid image reference | Uses the first tag from the metadata action, already lower-cased |
| `trivy-action@0.28.0`, pinned from memory, does not exist (found by the first run on GitHub) | The `Image` job could not start | Pinned to `v0.36.0` by commit SHA |
| Dependencies with known vulnerabilities | Local scan: 12 critical and 32 high in Java libraries, 3 high in Alpine | See section 2 |

## 2. Vulnerabilities

The first scan of the Phase 16 image failed the gate, because Spring Boot 3.5.7 brought in outdated Tomcat, Spring Security, Spring Framework, Netty and Jackson.

| Step | Java findings (critical + high) |
|---|---|
| Spring Boot 3.5.7, Bouncy Castle 1.82 | 44 |
| Spring Boot 3.5.16, Bouncy Castle 1.84 | 16 |
| Overrides: Tomcat 10.1.59, Netty 4.1.137, Jackson 2.21.7, PostgreSQL driver 42.7.12, Bouncy Castle 1.85; plus `apk upgrade` in the image | 0, with the 2 exceptions below |

Scan command: the same as `docs/modules/CICD.md` section 5, with `--ignore-unfixed` and `--exit-code 1`, so a local pass predicts the CI gate.

### Accepted exceptions (`.trivyignore.yaml`, expire 2027-02-01)

| CVE | Feature | Why accepted | Guard |
|---|---|---|---|
| CVE-2026-47884 | Spring MVC `XsltView` | Fixed only in Spring Framework 7 (Boot 4); this API renders no views | `NoUnusedVulnerableFeaturesTest` |
| CVE-2026-47890 | Server-Sent Events with view rendering | Fixed only in Spring Framework 7 (Boot 4); this API streams no SSE | `NoUnusedVulnerableFeaturesTest` |

On the expiry date the scan fails again, and the decision must be renewed or closed by moving to Spring Boot 4.

## 3. Not verified

| Item | Why | How it gets verified |
|---|---|---|
| The workflow runs on GitHub | Actions cannot run locally; only its YAML syntax was checked | The first push of this commit |
| The action's handling of `.trivyignore.yaml` | The local scan used Trivy 0.57.1 directly, with `--ignorefile`; the action may bundle another version | The first run's Scan step |
| Publishing to GHCR | Only runs on `main` and `v*` tags | The first merge to `main` |
| Whether the two ArchUnit guards would catch a real use | They pass because nothing uses those classes; no violation was tried | Accepted: the rules name the classes directly |

## 4. Security review

| Risk | Handling |
|---|---|
| A workflow token that can do too much | Read-only by default; only the image job may write packages |
| Pull requests from forks publishing images | Login and publish run only for pushes to `main` and tags |
| A compromised action version | The third-party scanner is pinned by commit SHA; first-party `actions/*` and `docker/*` use major tags. All updated weekly by Dependabot |
| Vulnerabilities appearing after a build | Only scanned when CI runs. A scheduled nightly scan of the published image is the next step |
| The Spring Boot 3.5.16 BOM lags behind security patches | The overrides close the gap but must be maintained by hand; moving to Boot 4 is the real answer |

### Accepted risks and next steps

| Item | Decision |
|---|---|
| Spring Boot 4 migration | Planned separately: a major upgrade with breaking changes (Jackson 3, among others) |
| Branch protection on `main` | A repository setting, to be applied by the owner (CICD.md section 3) |
| No deployment step | No hosting environment yet; delivery stops at the registry |
