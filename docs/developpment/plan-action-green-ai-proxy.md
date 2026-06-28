# Action Plan — Green AI Proxy & Router

**Role adopted:** Senior software architect (Java / Spring Boot 4 / MLOps).
**Technical target:** Java/Spring portfolio, solo project, incremental MVP-first delivery.
**Locked stack:** Java 25 LTS (Virtual Threads + Scoped Values), Spring Boot 4.0, Spring AI 2.0, PostgreSQL + pgvector (single database), Docker Compose.

> **Decisions made (iteration 2)** — (1) Java 25 LTS rather than 21: same Boot 4 base, ~8-year support, finalized Scoped Values. (2) A single database: PostgreSQL + the pgvector extension for the vector cache **and** the relational metrics (instead of Qdrant + Postgres). (3) Ingress/egress distinction made explicit: **ingress** in OpenAI-compatible format, **egress** to Claude (Anthropic) first.

---

## Foundational architecture decision

### The two axes not to confuse: ingress vs egress

- **Ingress** = the format *your clients* speak when calling the proxy. The market's de facto standard: the **OpenAI-compatible** format (`/v1/chat/completions`). A dev changes their `base_url` and their existing SDK works.
- **Egress** = the provider *you* call behind it (Anthropic, OpenAI, Ollama…). Hidden by Spring AI's `ChatModel`/`ChatClient` interface.

These two axes are **orthogonal**. We therefore start with **OpenAI ingress + Claude egress**. Changing or adding a provider = adding a starter + a bean, without touching the business code. This is what real LLM gateways do.

### One chain, not three applications

The brief's three pillars are not three applications. They are **three links of a single processing chain**:

```
Client → OpenAI ingress (/v1/chat/completions)
   → Gateway (OpenAI DTO → Spring AI Prompt mapping)
      → [Advisor 1] Semantic cache   ── hit → immediate reply (short-circuit)
      → [Advisor 2] Router           ── picks the target ChatClient
      → [Advisor 3] Green accounting ── measures € cost + gCO2
         → egress: real ChatModel (Claude / OpenAI / local Ollama)
      ← response bubbling back through the chain (cache write + metrics persistence)
   ← Spring AI response → OpenAI DTO mapping → Client
```

Spring AI natively provides the **Advisors API** (`CallAdvisor`/`StreamAdvisor`): each advisor can read, modify, **or block** the request before it reaches the model. This is exactly the mechanism for the cache (short-circuit) and routing. You therefore build a thin *gateway* around an advisor chain, not an over-engineered machine.

Spring AI **does not provide** a ready-made semantic-cache advisor: you will write it. That is precisely what creates the portfolio value (an existing RAG advisor to reuse as a code template, but custom logic).

---

## MVP definition

The demonstrable MVP = **Phases 0 to 2 + a thin slice of Phase 3**.
At that stage you have: a transparent proxy, a working semantic cache, basic cloud/local routing, and metrics persistence. That is already a demo that stands on its own. The rest (advanced green reporting, dashboard) is capitalization.

---

## Phase 0 — Foundations & infrastructure

Goal: a skeleton that starts, talks to an LLM and to a vector database.

| Task | Detail | Spring / Spring AI components |
|---|---|---|
| 0.1 | Bootstrap via `start.spring.io` | Boot 4.0, **Java 25** |
| 0.2 | Add the starters | `spring-ai-starter-model-anthropic` (primary egress), then `...-model-openai`, `...-model-ollama`, **`spring-ai-starter-vector-store-pgvector`**, `spring-boot-starter-web`, `...-data-jpa`, `postgresql`, `...-actuator`, `...-security` |
| 0.3 | Dev Docker Compose | **A single PostgreSQL** (pgvector extension enabled) + Ollama (added in 0.5). Use **Boot's Docker Compose support** for auto-start in dev. Fewer containers = less RAM = consistent with "green" |
| 0.4 | Concurrency | `spring.threads.virtual.enabled=true` (Virtual Threads, stable). **Scoped Values** (finalized in Java 25) to propagate the client/tenant id + trace through the chain without passing it everywhere |
| 0.5 | **Local** embedding | Ollama + `nomic-embed-text` (768 dimensions). Crucial: the embedding stays on-premise → consistent with the "zero leak" pillar. Check that the `vector` column dimension matches |
| 0.6 | Smoke test | A `ChatClient` (Claude egress) that replies + an `EmbeddingModel` that vectorizes + Actuator health check |

**Guardrail:** first validate the local-embedding → pgvector (**HNSW** index, **cosine** operator) → `similaritySearch` chain before anything else. It is the least familiar component, hence the riskiest.

---

## Phase 1 — The gateway (the backbone)

Goal: a transparent proxy (OpenAI ingress → Claude egress) that logs everything. Demonstrable on its own.

| Task | Detail | Components |
|---|---|---|
| 1.1 | OpenAI-compatible ingress DTOs | Java `records` for the `/v1/chat/completions` request/response. This is what lets any existing client SDK point at your proxy **without changing a line** → major adoption argument |
| 1.2 | Gateway controller | Map OpenAI DTO (ingress) → Spring AI `Prompt` → `ChatClient.call()` on the **Claude egress** → remap response → OpenAI DTO. At this stage: pure pass-through. The input format and the output provider are independent |
| 1.3 | Request persistence | `RequestLog` entity (JPA, **same PostgreSQL** as the cache): timestamp, model, prompt hash, in/out tokens, latency. Read the tokens via the **`ChatResponse` metadata** (Spring AI observability exposes them) |
| 1.4 | Authentication | Spring Security filter: API key → client (DB table). Lays the foundation for multi-tenancy and per-client reporting |

---

## Phase 2 — Pillar A: semantic cache (custom advisor)

Goal: intercept redundant requests before any model call.

| Task | Detail | Components |
|---|---|---|
| 2.1 | `SemanticCacheAdvisor` | `implements CallAdvisor, StreamAdvisor`. **Low** `getOrder()` → runs first in the chain |
| 2.2 | Hit/miss logic | Embed the user text → `vectorStore.similaritySearch()` with `threshold ≥ 0.92`, `topK = 1`. **Hit** → build a `ChatClientResponse` from the stored reply and **do not call** `chain.nextCall()` (short-circuit). **Miss** → let it through |
| 2.3 | Write on the way back | On a miss, when the chain returns: store `{question embedding, question text, response}` in **pgvector** with metadata (model, client, date) |
| 2.4 | Configuration | Threshold, TTL, **per-client namespace** via metadata filter (`Filter.Expression`) to partition the caches. Log hit/miss + compute € and gCO2 saved |

**Code crux (illustrative):**
```java
public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
    var hit = cacheLookup(req);            // embed + similaritySearch
    if (hit.isPresent()) {
        return buildResponseFromCache(hit.get());  // short-circuit: no LLM call
    }
    var response = chain.nextCall(req);    // miss → delegate to the rest
    cacheStore(req, response);             // learn for next time
    return response;
}
```

**Pitfall to anticipate:** streaming (`adviseStream`) complicates the short-circuit (you must emit a synthetic `Flux`). Implement the `call` (non-streaming) mode first, handle streaming in V2.

**Reversibility:** your advisor depends on the `VectorStore` interface, not on pgvector directly. If one day you must scale (tens of millions of vectors, very high throughput), you switch to Qdrant by changing the dependency and the config — **not your logic**. A low-risk decision.

---

## Phase 3 — Pillar B: smart router

Goal: send each request to the cheapest/leanest model that can handle it.

| Task | Detail | Components |
|---|---|---|
| 3.1 | Model registry | Config (`@ConfigurationProperties`): per model → provider, id, €/1k tokens, energy intensity, tier (local / cloud entry / cloud premium) |
| 3.2 | Multiple `ChatClient`s | One `ChatClient` bean per target, distinguished by `@Qualifier` (officially recommended routing pattern). E.g. `localClient` (Ollama), `cheapCloudClient`, `premiumClient` |
| 3.3 | Complexity classifier — V1 | Pure heuristics: length, code-block detection, keywords ("refactor", "demonstrate", "architecture"). Returns a tier. Zero cost, zero latency |
| 3.4 | `RoutingService` | Classifier → `ChatClient` selection. Wired into the flow after the cache miss |
| 3.5 | Classifier — V2 | Call to a small/cheap local model returning a JSON label (Spring AI `Structured Outputs` → POJO). Rules and thresholds hot-configurable |

---

## Phase 4 — Pillar C: green inference & reporting

Goal: measure, minimize and report the carbon footprint.

| Task | Detail | Components |
|---|---|---|
| 4.1 | Carbon model | tokens → estimated kWh (per-model coefficient) → gCO2 via grid intensity. Start with static regional coefficients |
| 4.2 | Real-time intensity | `CarbonIntensityProvider` interface (swappable/mockable) plugged into an ElectricityMaps / WattTime-type API. The abstraction protects you from coupling and eases testing |
| 4.3 | Persistence | Cost + carbon per request. Compute the **"avoided CO2"** = (emissions of a premium-default call) − (actual emissions after cache + routing). That number is what sells the solution |
| 4.4 | Temporal/geo routing | Async endpoint (`@Async` + queue): a `@Scheduled` worker dequeues and dispatches to the region where carbon intensity is lowest at execution time |
| 4.5 | Reporting API | Aggregates (€ saved, gCO2 avoided, cache hit rate, model mix) by date range. **Turnkey CSV/PDF export** for CSR departments (CSRD angle) |

---

## Phase 5 — Dashboard

Goal: administration + business visualization.

### Technology choice: **Svelte + Vite (static SPA)**

A deliberate decision, **consistent with the green theme**: among React/Angular/Vue/Svelte,
**Svelte** is the lightest (compiler, no runtime, no virtual DOM → smallest bundle, less JS to
parse/execute → less client-side energy). We stay on **Svelte + Vite as a static SPA** (not
SvelteKit: no Node runtime, we only serve static assets). For charts: a lightweight lib
(**uPlot** or native SVG), definitely **not** Chart.js/D3.

### Mono-repo (adapt the existing repo, no second repository)

```
src/main/
├── java/…                ← Spring Boot back end (unchanged)
├── resources/…           ← back-end config
└── frontend/             ← Svelte + Vite app (new)
    ├── package.json
    ├── vite.config.ts    ← build.outDir → ../../../target/classes/static ; /v1 proxy in dev
    └── src/…
```

- **Build orchestrated by Maven** via `frontend-maven-plugin`: installs a *local* Node (pinned,
  reproducible, no global Node), runs `npm ci` + `npm run build`. Vite emits the assets into
  `target/classes/static` → **bundled in the jar**, served by Spring Boot. A single
  `./mvnw package` produces a self-sufficient deliverable (consistent with **on-premise**).
- **`./mvnw test` stays Node-free**: the frontend build is bound to the `prepare-package` phase
  (after `test`), behind a **disableable** profile (`-DskipFrontend`) for back-end-only work.
- `.gitignore`: `src/main/frontend/node_modules` and `dist`/build output.

### Serving & security

- Spring serves `index.html` + static assets. `SecurityConfig` must **`permitAll`** the shell
  (`/`, `/assets/**`, `/favicon.ico`) while keeping `/v1/**` **authenticated**.
- The dashboard calls the API with the **API key as Bearer** (entered by the user, kept in
  memory). CSV/PDF downloads via `fetch` + `Blob` (an `<a href>` can't carry the auth header).
  **Accepted trade-off**: key in the browser = acceptable for a self-hosted internal tool; a
  session-based auth is a Phase 6 evolution.

### Dev workflow

- `npm run dev` → Vite on `:5173` with a **`/v1` → `localhost:8080` proxy** (hot reload).
- Prod build = `./mvnw package` (frontend bundled in the jar).

| Task | Detail |
|---|---|
| 5.0 | **Foundation**: Svelte+Vite project in `src/main/frontend`, `frontend-maven-plugin`, static serving + `SecurityConfig`, shell + API key input |
| 5.1 | API key admin — client/key CRUD (requires back-end admin endpoints) |
| 5.2 | Routing config — adjust thresholds and rules at runtime |
| 5.3 | Live metrics — € and gCO2 over time, hit rate, model mix (consumes `/v1/reports/green`) |
| 5.4 | Reports — generate and download the CSRD exports (CSV/PDF already in place on the back end) |

---

## Phase 6 — Engineering polish

| Task | Detail | Portfolio argument |
|---|---|---|
| 6.1 | Observability | Native Spring AI observability (tokens, latency, model) → Micrometer → Prometheus + Grafana | MLOps maturity |
| 6.2 | Rate limiting | Bucket4j or Boot 4's native concurrency limits | Robustness |
| 6.3 | GraalVM native image | Optional native compilation | Fast startup + minimal RAM = consistent with the "green" narrative, double win |
| 6.4 | MCP exposure ✅ | Spring AI 2.0 integrates MCP at its core: expose the gateway as an MCP server (see `docs/technical/mcp.md`) | Strong differentiation |
| 6.5 | Final packaging ✅ | Multi-stage `Dockerfile` + "plug & play" `docker-compose.yml` (gateway + pgvector + Ollama), `.env.example`, README + end-to-end architecture diagram | Demonstrates the end-to-end architecture |

---

## Phase 7 — v1 release readiness (pre-launch hardening)

Came out of a post-v1 review (2026-06-28). The first full boot revealed that the
default configuration cannot actually serve a request against today's Anthropic
API, and that wiring regressions slip through the default test run. **7.1–7.3 are
blocking — they must be cleared before calling this a shippable v1.** 7.4+ are
hardening that overlaps [`roadmap-post-v1.md`](roadmap-post-v1.md).

| Task | Severity | Detail |
|---|---|---|
| 7.1 ✅ | **Blocking** | **Fix the model-registry IDs.** The defaults were stale/invalid: `claude-sonnet-4-20250514` (premium) is deprecated/retired (~2026-06-15) and `claude-haiku-4-20250506` (entry) never existed — both 404. **Done:** registry now uses current aliases — premium `claude-opus-4-8` (key `claude-opus`), entry `claude-haiku-4-5`. Swap premium to `claude-sonnet-4-6` for a cheaper tier. (`local`/`llama3` still pending 7.2.) |
| 7.2 ✅ | **Blocking** | **Repair `LOCAL`-tier routing.** Short prompts used to route to `llama3` on the Anthropic egress → 404. **Done:** wired a real multi-provider egress — re-enabled `OllamaChatAutoConfiguration`, added a `@Primary DelegatingChatModel` that dispatches per request (by the routed model id's provider) to the Anthropic or Ollama `ChatModel`, repointed the `local` tier to `qwen2.5:0.5b` (pulled at startup). Verified in-container: short prompt → Ollama (200, free), complex → Anthropic. Also unlocks free local testing (7.4). |
| 7.3 | **Blocking** | **End-to-end context test in CI.** The full Spring context had never booted before v1 — two startup bugs (MCP bean cycle, missing `CarbonAwareZoneSelector` bean) only surfaced on first container run. The default `./mvnw test` never refreshes the context (`GatewaiApplicationTests` just does `new`). Run the `@SpringBootTest` smoke tests in CI (`./mvnw -Pit test`, needs Postgres + Ollama) and/or add a lightweight context-load test so wiring regressions fail the build. |
| 7.4 | Hardening | **Cheap/offline testing.** Free path: route to a tiny local Ollama model (depends on 7.2) to exercise cache + routing + green accounting at zero API cost. Optional: a mock/"echo" `LlmClient` behind a Spring profile for pure plumbing tests with no provider. |
| 7.5 | Hardening | **Streaming.** Honor `stream: true` end to end, including a synthetic-`Flux` short-circuit in the cache advisor (today `adviseStream` just delegates and the controller only does `call`). |
| 7.6 | Hardening | **Carbon calibration.** Replace the placeholder per-model `energyIntensity` coefficients with measured values; consider marginal grid intensity (WattTime) for trustworthy figures. |
| 7.7 | Hardening | **Cluster-readiness.** Replace the in-memory pieces that assume a single node: persistent deferred-job store + distributed rate limiter (Redis/Hazelcast); move from `ddl-auto=update` to versioned migrations (Flyway/Liquibase). |

**Acceptance for "v1 shippable":** with default config, a fresh `docker compose
up` serves a real `POST /v1/chat/completions` for short, medium and long prompts
(all three tiers resolve to a served model), and CI runs an end-to-end context
check.

---

## Platform notes (pitfalls to know from the start)

**Java 25**
- **Scoped Values**: finalized, production-ready. Use them to propagate the request context (client, trace) across Virtual Threads and the advisor chain.
- **Structured Concurrency**: **still in preview** (JEP 505, `--enable-preview`, API subject to change). Do not make the core logic depend on it. For parallel fan-out (e.g. the carbon worker), stick to a Virtual Thread `ExecutorService` / `CompletableFuture` for now.
- Compatibility: it comes from the **Spring Boot 4 BOM**. By generating the project on `start.spring.io` and letting the BOM manage versions, most of it is settled. Only libs outside the Spring ecosystem (e.g. a future carbon API client) need a manual JDK-baseline check.

**Spring AI 2.0**
- **Immutable builders mandatory**: `AnthropicChatOptions.builder().model(...).temperature(...).build()` — the setters are gone.
- **Jackson 3**: packages moved from `com.fasterxml.jackson` to `tools.jackson`. Check your OpenAI DTO (ingress) deserializations.
- **Conversational memory**: if you add `ChatMemory`, an explicit `conversationId` is now mandatory (no more `DEFAULT_CONVERSATION_ID`). Non-blocking for the MVP.
- **JSpecify / null-safety**: annotated everywhere — beneficial, but be rigorous about optionality.

**PostgreSQL + pgvector**
- Enable the `vector` extension and create the **HNSW** index with the **cosine** operator (consistent with the 0.92 similarity threshold). Without the index, the search scans the whole table.
- The `vector` column dimension = the embedding model's dimension (768 for `nomic-embed-text`). Well below pgvector's indexing limits.

---

## Link to your positioning

This project ticks all four of your repositioning axes at once:
- **Architecture**: advisor pipeline, gateway, multi-model routing.
- **Orchestration**: local/cloud arbitration, temporal/geographic routing.
- **Cybersecurity**: on-premise, zero transit of keys and data through a third party.
- **Business→technical translation**: CSRD reporting is literally a regulatory constraint turned into a quantifiable technical feature.

The demo narrative is not "I plugged in an LLM API", it is "I cut the cost and carbon footprint of enterprise AI, without compromising privacy, and I prove it in euros and gCO2".
