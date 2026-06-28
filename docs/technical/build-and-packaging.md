# Build & packaging

How the project is built (back end + bundled dashboard) and shipped (Docker, plug
& play compose, native image). Sources: `pom.xml`, `Dockerfile`,
`docker-compose.yml`, `compose.yaml`, `src/main/frontend`.

## Maven build

- Wrapper `./mvnw`; Spring Boot parent BOM manages versions (Spring AI via
  `spring-ai-bom`).
- `./mvnw test` — fast, **Node-free**, no containers.
- `./mvnw verify` — runs **Checkstyle** (validate phase, fail-fast), **tests**, and
  **SpotBugs** (verify phase). See [`testing-and-quality.md`](testing-and-quality.md).
- `./mvnw package` — also builds the frontend and bundles it in the jar.

## Frontend mono-repo

The Svelte + Vite dashboard lives in `src/main/frontend` and is built **by Maven**
via `frontend-maven-plugin` (profile `frontend`, active unless `-DskipFrontend`):

- Installs a **pinned, local Node** (into `target/`), runs `npm ci` then
  `npm run build`.
- Vite emits assets into `target/classes/static`, so they are **bundled in the
  jar** and served by Spring Boot — one self-sufficient deliverable (on-premise
  friendly).
- Bound to the `prepare-package` phase, so `./mvnw test` stays Node-free.
- Dev: `npm run dev` (Vite on `:5173`, proxies `/v1 → :8080`).

## Docker image (multi-stage)

`Dockerfile`:

- **Build stage** (`eclipse-temurin:25-jdk`): copies `.mvn`, `mvnw`, `pom.xml`,
  `checkstyle.xml`, `spotbugs-exclude.xml`, `src`, then `./mvnw -DskipTests clean
  package` (frontend included). A BuildKit cache mount keeps `~/.m2` warm.
- **Runtime stage** (`eclipse-temurin:25-jre`): adds `curl` (for the health
  check), runs as a **non-root** user, copies the fat jar, sets
  `SPRING_DOCKER_COMPOSE_ENABLED=false`, exposes `8080`, and defines a
  `HEALTHCHECK` hitting `/actuator/health`.

> Lesson encoded in the Dockerfile: `checkstyle.xml`/`spotbugs-exclude.xml` must be
> in the build context because Checkstyle runs at the `validate` phase during
> `package`.

## Compose: two files, on purpose

- **`compose.yaml`** — infra only (pgvector + Ollama), with Spring Boot
  service-connection labels. Spring Boot's Docker Compose support **auto-starts it
  in dev** (`./mvnw spring-boot:run`) and it takes precedence for a bare
  `docker compose` command.
- **`docker-compose.yml`** — the **plug & play full stack**: gateway + pgvector +
  Ollama, `depends_on … service_healthy`, env-driven config, secrets via `.env`.
  Invoked explicitly: `docker compose -f docker-compose.yml up --build`.

They are separate so that running the app in dev (`spring-boot:run`) does not also
try to launch a `gateway` container (port 8080 clash). The gateway container sets
`SPRING_DOCKER_COMPOSE_ENABLED=false`, connects to `pgvector`/`ollama` by service
name, and pulls the embedding model from Ollama on first start.

- **`docker-compose.observability.yml`** — optional Prometheus + Grafana stack (see
  [`observability.md`](observability.md)).

## Configuration surface

Runtime config is environment-overridable (Spring relaxed binding), e.g.
`ANTHROPIC_API_KEY`, `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`,
`SPRING_AI_OLLAMA_BASE_URL`, `ELECTRICITY_MAPS_TOKEN`. `.env.example` documents the
plug & play variables; `.env` is git-ignored.

## Native image (optional)

The `native` profile (from the Spring Boot parent) builds a GraalVM native image;
reflection runtime hints are declared for the reflectively-bound DTOs and tool
results. Full native compilation is validated in a GraalVM CI, not here. Details
and caveats: [`native.md`](native.md).
