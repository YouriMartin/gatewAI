# Technology stack

Why each piece is here, and the consequences for the code.

## Java 25 LTS

- **Virtual Threads** (`spring.threads.virtual.enabled=true`) — the gateway is
  I/O-bound (it mostly waits on provider APIs and Postgres). Virtual threads give
  a simple thread-per-request model without a thread-pool bottleneck.
- **Scoped Values** (finalized in Java 25) — used to propagate the request context
  (`RequestContext`: clientId, trace) across the advisor chain and virtual threads
  without passing it through every method. See `RequestContext.CURRENT` and its use
  in `SemanticCacheAdvisor` (namespacing) and `ChatCompletionService` (per-client
  attribution). A second Scoped Value, `CarbonZoneContext.CURRENT`, carries the
  chosen zone into green accounting for deferred jobs.
- **Structured Concurrency is deliberately NOT used** — it is still a preview
  feature (API subject to change). The core must not depend on a preview API. For
  parallel fan-out (e.g. reading per-zone carbon intensities) the code stays on
  plain loops / `ExecutorService` / `CompletableFuture`.

LTS choice: Java 25 over 21 buys finalized Scoped Values and a long support window
on the same Spring Boot 4 baseline.

## Spring Boot 4.0

- The application framework: DI, web (Spring MVC), Security, Data JPA, Actuator,
  Docker Compose support (auto-starts infra in dev), and the test slices.
- Consequence: the **Docker Compose support** is for dev only and is disabled in
  the container image (`SPRING_DOCKER_COMPOSE_ENABLED=false`).

## Spring AI 2.0

- Provides the `ChatClient`/`ChatModel` abstraction (egress), the **Advisors API**
  (`CallAdvisor`/`StreamAdvisor`) that the cache and router implement, the
  `VectorStore` abstraction, `EmbeddingModel`, Structured Outputs (used by the LLM
  classifier), and the **MCP server** starter.
- Consequences in the code:
  - **Immutable builders** everywhere (`ChatOptions.builder()...build()`); there
    are no setters.
  - **Jackson 3** — DTO (de)serialization uses the `tools.jackson` package, not
    `com.fasterxml.jackson`.
  - Native model/usage **observability** is automatic when Actuator + Micrometer
    are present.

## PostgreSQL + pgvector (single database)

- **One** database holds both the **vector cache** (pgvector) and the
  **relational metrics** (`request_log`, `api_client`). Fewer moving parts, fewer
  containers, less RAM — consistent with the green stance.
- The vector store is used **through Spring AI's `VectorStore` interface**, never
  pgvector directly, so it can be swapped for Qdrant by changing a dependency and
  config — not the cache logic.
- Config: HNSW index, cosine distance, 768-dimension vectors (matching the
  embedding model), `initialize-schema=true`.

## Ollama (local embeddings + local chat egress)

- `nomic-embed-text` (768 dims) runs **locally** via Ollama. Embeddings — the one
  step that sees raw prompt text for the cache — never leave the deployment, which
  is core to the privacy pillar.
- Since Phase 7.2, Ollama also serves a **local chat model** (`qwen2.5:0.5b` by
  default) as the egress for the `LOCAL` routing tier — real on-prem, zero-cost
  inference for simple prompts (see [`routing.md`](routing.md)).
- Both models are pulled on first start (`pull-model-strategy=when_missing`).

## Build & tooling

- **Maven** (wrapper `./mvnw`), with the **frontend** built by
  `frontend-maven-plugin` into the jar (see
  [`build-and-packaging.md`](build-and-packaging.md)).
- **Checkstyle** (validate phase, Google style) and **SpotBugs** (verify phase) for
  static analysis; **ArchUnit** for architecture; **JUnit 5 + Mockito** for tests.

## Notable platform gotchas

- Spring AI immutable builders — porting older snippets that used setters will not
  compile.
- Jackson 3 package move — `@JsonNaming`/imports come from `tools.jackson`.
- pgvector dimension must equal the embedding dimension (768) or inserts fail.
- Virtual threads + Scoped Values pair well; Scoped Values are the right context
  mechanism here (not `ThreadLocal`, which does not propagate cleanly and is
  mutable).
