# syntax=docker/dockerfile:1.7
#
# SyncTank contract-platform — Day 10 container image.
#
# Two stages:
#   build   — Maven + full JDK. Compiles, RUNS THE UNIT TESTS, packages the Boot jar, then
#             splits it into layers so a code-only change re-ships ~100 KB instead of ~100 MB.
#   runtime — JRE only, plus exactly two OS tools the platform genuinely execs:
#               ripgrep : UsageScanner shells out to `rg` (Day 05/06). Missing rg used to make
#                         the registry seed silently empty — see audit finding F1.
#               curl    : the HEALTHCHECK below, and ECS's container health check on Day 11.
#
# Nothing secret is ever baked in. Credentials arrive at runtime from AWS Secrets Manager
# (SECRETS_PROVIDER=aws, Day 09) or environment variables (SECRETS_PROVIDER=env, CI).
# .dockerignore keeps local credential files out of the build context entirely.

############################
# Stage 1 — build
############################
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Tests run by default: an image cannot be produced from code whose tests fail. This is also
# the first place contract-platform's unit tests run outside a developer laptop (finding F5).
# Fast local iteration: docker build --build-arg SKIP_TESTS=true ...
ARG SKIP_TESTS=false

COPY pom.xml .
COPY src ./src

# BuildKit cache mount: ~/.m2 survives between local builds without becoming an image layer,
# so only the first build downloads the dependency tree. (A separate `dependency:go-offline`
# layer is deliberately not used — it misses artifacts Surefire resolves at test time, and the
# offline build then fails for a reason that has nothing to do with the code.)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp package -DskipTests=${SKIP_TESTS} \
 && cp target/contract-platform-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

############################
# Stage 2 — runtime
############################
FROM eclipse-temurin:21-jre-noble AS runtime

# ripgrep + curl, nothing else. `rg --version` in the same RUN fails the build if the package
# ever disappears — F1 becomes a build error instead of a silently empty registry.
# DL3008 (pin apt versions) is waived: Ubuntu security updates replace exact versions within
# weeks, so a pin breaks the build on a schedule. The base image tag is the pin that matters.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends ripgrep curl \
 && rm -rf /var/lib/apt/lists/* \
 && rg --version \
 && groupadd --system --gid 10001 synctank \
 && useradd --system --uid 10001 --gid synctank --no-create-home \
            --home-dir /nonexistent --shell /usr/sbin/nologin synctank

WORKDIR /app

# Least-changing layer first. Only `application/` changes on an ordinary code edit.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

# Numeric uid:gid — lets an orchestrator verify "runs as non-root" without reading /etc/passwd.
USER 10001:10001

# MaxRAMPercentage: size the heap from the container's memory limit, not the host's.
# ExitOnOutOfMemoryError: die cleanly and let the orchestrator restart us, rather than limp on.
# The JVM prints "Picked up JAVA_TOOL_OPTIONS: ..." on stderr at startup — expected, harmless.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8081

# Liveness only — /health answers UP once Tomcat is serving. start-period covers the ~20 s boot.
# ECS ignores this instruction and uses the task definition's healthCheck (infra/aws/ecs/).
# Shell form on purpose (DL3025 waived): curl -f exits 22 on an HTTP error, and Docker reserves
# every exit code except 0 (healthy) and 1 (unhealthy) — `|| exit 1` needs a shell.
# hadolint ignore=DL3025
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8081/health || exit 1

# Exec form: the JVM is PID 1 and receives SIGTERM directly, so `docker stop` and an ECS
# deployment get Spring's graceful shutdown instead of a SIGKILL after the timeout.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]