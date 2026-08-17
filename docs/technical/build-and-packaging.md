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

## The embedding model is fetched, not committed (v3 lot A)

The in-process embedding model — `model.onnx` (118 MB, int8) and
`tokenizer.json` (17 MB) — is **not in git**. `download-maven-plugin` fetches it
at `generate-resources` straight into `target/classes/onnx/<model>/`, verified by
a pinned **SHA-256**, so the packaged jar still carries it and the shipped
artifact is unchanged.

```bash
./mvnw generate-resources    # or any build: test, package, verify
```

Two reasons it is not a committed blob, and one it is not Git LFS:

- `model.onnx` is **over GitHub's 100 MB per-file hard limit** — a plain commit
  cannot be pushed at all.
- Git LFS clears that limit but spends an **account-wide 1 GB/month bandwidth
  quota** on every clone and every CI checkout. At 135 MB a fetch, two CI jobs
  per push, the free quota is gone in three or four pushes.
- A binary that is replaced whole and never diffed is a *dependency*, not source.
  A cold build already downloads far more than this from Maven Central.

Cached under `~/.m2/repository/.cache/download-maven-plugin` (130 MB), which is
inside the directory CI already caches — so the download happens once per cache
generation, and `./mvnw -o` works offline afterwards. Swapping the model means
changing two URLs, two checksums and the two
`spring.ai.embedding.transformer.*` properties.

`InProcessEmbeddingModelTest` asserts the resources exist and are megabytes, so a
failed or truncated fetch fails a test rather than the application at startup.
The Docker build runs Maven inside the image, so it fetches there too — the build
stage already needs network for dependencies.

**In the container, the model is copied out of the jar at startup.** A resource
inside a jar cannot be memory-mapped, so Spring AI's `ResourceCacheService`
writes it to `spring.ai.embedding.transformer.cache.directory`
(`${java.io.tmpdir}/gatewai-onnx`, overridable with `GATEWAI_ONNX_CACHE`):
**130 MB, measured**, on first start only. Running from an exploded classpath
(`./mvnw spring-boot:run`, tests) copies nothing — the log says which it did.
Budget the disk, and mount a volume there if the container's `/tmp` is small or
read-only. Measured cold start of the packaged image: **8.0 s**, with no model
server running.

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

## Compose: four files, on purpose

- **`compose.yaml`** — infra only, with Spring Boot service-connection labels.
  Spring Boot's Docker Compose support **auto-starts it in dev**
  (`./mvnw spring-boot:run`) and it takes precedence for a bare `docker compose`
  command. Since v3 lot A it starts **pgvector alone**: embeddings are
  in-process, so nothing else is needed to decide. Ollama is still declared,
  under the `inference` profile — `docker compose --profile inference up -d`
  when you want local chat egress.
- **`docker-compose.yml`** — the **plug & play full stack**: gateway + pgvector +
  Ollama, `depends_on … service_healthy`, env-driven config, secrets via `.env`.
  Invoked explicitly: `docker compose -f docker-compose.yml up --build`.

They are separate so that running the app in dev (`spring-boot:run`) does not also
try to launch a `gateway` container (port 8080 clash). The gateway container sets
`SPRING_DOCKER_COMPOSE_ENABLED=false`, connects to `pgvector`/`ollama` by service
name, and pulls its chat models from Ollama on first start.

- **`docker-compose.observability.yml`** — optional Prometheus + Grafana stack (see
  [`observability.md`](observability.md)).
- **`docker-compose.cluster.yml`** — **two gateway replicas behind an nginx
  balancer** on one PostgreSQL (v3 lot B.5). Not a deployment template: it exists
  to be run, and `scripts/cluster-smoke.sh` runs the scenario against it. The
  `mock` egress is on, because it demonstrates clustering rather than inference
  and a real egress would add a ~3 GB model pull that tests nothing here.

  ```bash
  docker compose -f docker-compose.cluster.yml up --build -d
  ./scripts/cluster-smoke.sh
  docker compose -f docker-compose.cluster.yml down -v
  ```

  The balancer publishes `:8080`; the nodes are also on `:8081` and `:8082`,
  because some checks are about what a *particular* node believes. Two settings in
  it are the ones a real cluster needs: `GATEWAI_RATELIMIT_STORE=postgres` (or each
  replica grants the full quota on its own) and `GATEWAI_INSTANCE_ID` (which names
  the node in `claimed_by` and in the `instance` metric tag).

## Configuration surface

Runtime config is environment-overridable (Spring relaxed binding), e.g.
`ANTHROPIC_API_KEY`, `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`,
`OLLAMA_BASE_URL`, `ELECTRICITY_MAPS_TOKEN`. `.env.example` documents the
plug & play variables; `.env` is git-ignored.

## Native image (optional)

The `native` profile (from the Spring Boot parent) builds a GraalVM native image;
reflection runtime hints are declared for the reflectively-bound DTOs and tool
results. Full native compilation is validated in a GraalVM CI, not here. Details
and caveats: [`native.md`](native.md).
