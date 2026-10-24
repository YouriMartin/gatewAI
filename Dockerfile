# syntax=docker/dockerfile:1.28.0@sha256:bb22d9815c728170f72750f4e5b0d672e06176142e1d602c7e66c050100b7e5b

# Base images are pinned by digest (an index, so every architecture stays
# available) with the tag kept for reading; Dependabot moves both together.

# ---------------------------------------------------------------------------
# Stage 1 — build the fat jar (Svelte dashboard bundled via frontend profile).
# The frontend-maven-plugin downloads its own Node, so only a JDK is required.
#
# It runs on the *build* platform, never under emulation: the jar is
# architecture-independent — ONNX Runtime and the DJL tokenizer ship their
# linux-x64 and linux-aarch64 natives inside it and pick one at load time — so a
# multi-arch release builds it once, natively, and only stage 2 is per-arch.
# ---------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641 AS build
WORKDIR /workspace

# The release workflow passes the tag's version; a local build stays a SNAPSHOT.
ARG GATEWAI_VERSION=0.0.1-SNAPSHOT

COPY .mvn/ .mvn/
COPY mvnw pom.xml checkstyle.xml spotbugs-exclude.xml ./
COPY src/ src/

# A persistent BuildKit cache mount keeps the Maven repo (and the Node install
# the frontend plugin downloads) warm across builds. Tests run in CI, so the
# image build skips them for speed.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -B -DskipTests -Drevision="${GATEWAI_VERSION}" clean package \
    && cp target/gatewai-*.jar app.jar

# ---------------------------------------------------------------------------
# Stage 2 — slim runtime. curl is added for the container healthcheck against
# the unauthenticated /actuator/health endpoint.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f AS runtime
ARG GATEWAI_VERSION=0.0.1-SNAPSHOT
LABEL org.opencontainers.image.title="gatewAI" \
      org.opencontainers.image.description="Self-hosted LLM gateway: semantic cache, routing and carbon accounting" \
      org.opencontainers.image.source="https://github.com/YouriMartin/gatewAI" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${GATEWAI_VERSION}"
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as an unprivileged user.
RUN useradd --system --uid 10001 --create-home gatewai
USER gatewai
WORKDIR /app

COPY --from=build /workspace/app.jar app.jar

# The bundled docker-compose support is for local dev only; disable it in-container.
ENV SPRING_DOCKER_COMPOSE_ENABLED=false
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=10 \
    CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
