# syntax=docker/dockerfile:1.19
#
# SentinelFlow API container image.
#
# Two stages: the JDK, Maven and the source tree exist only in the build stage,
# so the runtime image carries a JRE and the application layers and nothing that
# could compile or fetch code.
#
# Base images are pinned to an exact patch build rather than a floating major.
# 25.0.4_7 is the newest Temurin 25 published to Docker Hub; the reference
# machine's local JDK is 25.0.4.1+1, one critical-patch build ahead, which
# Adoptium has not yet published as an image. Both are Java 25 LTS.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:26.0.2_10-jdk-noble AS build

# The Temurin image ships neither curl, wget nor unzip. Without them the Maven
# Wrapper silently falls back to a bundled Java downloader that does not follow
# the redirect Maven Central issues, saves the redirect body, and then fails
# checksum validation - which is exactly the confusing failure the checksum
# exists to make loud. Installing a real HTTP client makes the download path
# deterministic.
#
# Package versions are not pinned here: the base image tag is, and pinning
# against a rolling archive breaks the build the first time Ubuntu ships a
# security update for one of these.
RUN apt-get update     && apt-get install --no-install-recommends -y curl ca-certificates unzip     && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# The wrapper and the pom first, on their own layer: dependency resolution is
# the slow step and it only needs to rerun when the pom changes, not when a
# source file does.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

# Split the fat jar into layers ordered by how often they change, so a code
# change does not invalidate the dependency layer.
RUN java -Djarmode=tools -jar target/sentinelflow-api-*.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:26.0.2_10-jre-noble AS runtime

# curl is here for HEALTHCHECK only. Without a health probe in the image, an
# orchestrator can only tell that the process exists, not that it is serving.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a fixed non-root uid. A container that runs as root shares the host's
# uid 0 if a namespace mapping is ever missing.
RUN groupadd --system --gid 10001 sentinelflow \
    && useradd --system --uid 10001 --gid sentinelflow --no-create-home --shell /usr/sbin/nologin sentinelflow

WORKDIR /app

COPY --from=build --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/application/ ./

USER 10001:10001

EXPOSE 8080

# Readiness, not liveness: an orchestrator should route traffic only once the
# service can actually serve it.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness || exit 1

# JarLauncher, not `-jar`: `extract --launcher` produces an exploded
# application rooted at /app, not a jar. The launcher assembles the classpath
# from BOOT-INF and starts the main class.
#
# Exec form, so the JVM is pid 1 and receives SIGTERM directly. Wrapped in a
# shell it would not, and `make down` would wait out the stop timeout on every
# container.
#
# MaxRAMPercentage lets the JVM size its heap from the container limit rather
# than the host's memory. ExitOnOutOfMemoryError makes an OOM a restart rather
# than a process that stays up serving errors.
ENTRYPOINT ["java",     "-XX:MaxRAMPercentage=75.0",     "-XX:+ExitOnOutOfMemoryError",     "org.springframework.boot.loader.launch.JarLauncher"]
