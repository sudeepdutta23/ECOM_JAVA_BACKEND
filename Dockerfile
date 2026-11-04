# syntax=docker/dockerfile:1.7
#
# Production image: build with Maven, run on a JRE only, as a non-root user.
#   docker build -t ecommerce-backend .
# Tests are not run here: CI runs them (with Testcontainers) before building the image.
# Design: docs/modules/DOCKER.md

# ---------------------------------------------------------------- build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Dependencies first: this layer is rebuilt only when pom.xml changes, not on every code change.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/ecommerce-backend-*.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-alpine

# A fixed, unprivileged user: never root, and the same UID everywhere for volume permissions.
RUN addgroup -S -g 10001 app \
 && adduser -S -u 10001 -G app -H -s /sbin/nologin app \
 && mkdir -p /var/lib/ecommerce/files \
 && chown app:app /var/lib/ecommerce/files

WORKDIR /app
# Layers from least to most often changed, so a code change ships only the small last layer.
# Owned by root and not writable by the app: the code cannot be modified at runtime.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER 10001:10001

# Memory from the container's limit, not the host's; crash on OOM so the orchestrator restarts a
# clean JVM instead of keeping a half-broken one.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    FILES_LOCAL_DIR=/var/lib/ecommerce/files \
    SPRING_PROFILES_ACTIVE=prod

# 8080: the API. 8081: probes and metrics (prod profile); never publish it to the internet.
EXPOSE 8080 8081

# Exec form: the JVM is PID 1 and receives SIGTERM, so graceful shutdown finishes in-flight requests.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
