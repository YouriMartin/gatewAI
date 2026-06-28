# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build the fat jar (Svelte dashboard bundled via frontend profile).
# The frontend-maven-plugin downloads its own Node, so only a JDK is required.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml checkstyle.xml spotbugs-exclude.xml ./
COPY src/ src/

# A persistent BuildKit cache mount keeps the Maven repo (and the Node install
# the frontend plugin downloads) warm across builds. Tests run in CI, so the
# image build skips them for speed.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -B -DskipTests clean package \
    && cp target/gatewai-*.jar app.jar

# ---------------------------------------------------------------------------
# Stage 2 — slim runtime. curl is added for the container healthcheck against
# the unauthenticated /actuator/health endpoint.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
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
