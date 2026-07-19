# Green AI Proxy & Router — Project context

Open-source, self-hosted (**on-premise**) LLM proxy / gateway for enterprises:
it secures, caches, routes and measures the carbon footprint of AI requests.
A Java/Spring portfolio project, built solo.

## Stack
- **Java 25 LTS** (Virtual Threads + Scoped Values)
- **Spring Boot 4.0**, **Spring AI 2.0**
- **PostgreSQL + pgvector** — single database: vector cache **AND** relational metrics
- **Ollama** — local embeddings (`nomic-embed-text`, 768 dimensions)
- **Docker Compose** for local infra
- Build: **Maven** (wrapper `./mvnw`)

## Architecture (to follow strictly)
A single processing chain, not three applications. Thin gateway + Spring AI **Advisor** chain.

```
Client → OpenAI-format ingress (/v1/chat/completions) → DTO mapping → Prompt
   → [Advisor 1] semantic cache    ── short-circuits on hit (does not call chain.nextCall())
   → [Advisor 2] router            ── picks the target ChatClient
   → [Advisor 3] green accounting  ── € cost + gCO2
      → egress: real ChatModel (any configured provider mix; local-first default)
   ← remap response → OpenAI DTO → Client
```

Non-negotiable principles:
- **Ingress** (the format clients speak = OpenAI) and **egress** (the provider called) are **independent**.
- Egress is **provider-agnostic and local-first**: provider instances are declared under
  `gatewai.providers.<name>` (anthropic | openai | openai-compatible | ollama), the model registry
  references them by name, and there is **no fallback provider** (unknown model id → 400). No API
  key is required by default — all tiers run on Ollama.
- The custom cache implements `CallAdvisor`/`StreamAdvisor`, low `getOrder()`, short-circuits by **not calling** `chain.nextCall()`.
- All persistence (the `RequestLog` entity + cache vectors) goes into the **same PostgreSQL**.
- Depend on the `VectorStore` interface, **never** on pgvector directly (reversibility toward Qdrant).
- **Structured Concurrency = preview → DO NOT use it** in the core. **Scoped Values = OK** (client/trace context propagation).

## Commands
- Tests: `./mvnw test`
- Run the app: `./mvnw spring-boot:run` (Boot starts Postgres + Ollama via `compose.yaml`)
- Infra only: `docker compose up -d`
- Pull the embedding model: `docker compose exec ollama ollama pull nomic-embed-text`
- **Always run `./mvnw test` before committing.**

## Hexagonal architecture (packages)

```
io.github.yourimartin.gatewai
├── domain/model/            # Entities, value objects — zero Spring/JPA dependency
├── domain/port/in/          # Inbound ports (use cases)
├── domain/port/out/         # Outbound ports (persistence, LLM, vector store)
├── application/service/     # Application services — depend on domain only
├── infrastructure/          # Outbound adapters — implement out ports
│   ├── persistence/         # JPA
│   ├── llm/                 # ChatClient/ChatModel
│   └── vectorstore/         # VectorStore
└── adapter/in/web/          # REST controllers (OpenAI ingress)
```

**Dependency rules:**
- `domain` depends on nothing (no Spring, no JPA, no Spring AI)
- `application` depends on `domain` only
- `infrastructure` implements the `out` ports of `domain`
- `adapter.in.web` calls the `in` ports of `domain`
- These rules are enforced by **ArchUnit** (`ArchitectureTest.java`)

## Conventions
- Java `record` for DTOs.
- Spring AI 2.0 immutable builders (no setters).
- Jackson 3 → `tools.jackson` package (not `com.fasterxml.jackson`).
- Secrets via environment variables, **never committed** (`ANTHROPIC_API_KEY`).
- Short commit messages, **in English**, imperative mood.
- **Always propose a commit message at the end of each implementation.**

## Tests
- Naming: `{Class}Test.java` in the mirror package under `src/test/java`
- Unit tests on all classes **except**: REST controllers (integration-tested), trivial mappers
- `ArchitectureTest` validates the hexagonal rules via ArchUnit
- **Always run `./mvnw test` before committing**

## Linters & static analysis
- **Checkstyle** (`maven-checkstyle-plugin`) — `validate` phase, fail-fast, config `checkstyle.xml` (Google Style + overrides)
- **SpotBugs** (`spotbugs-maven-plugin`) — `verify` phase, effort=max, threshold=low
- `./mvnw verify` runs all three (Checkstyle + Tests + SpotBugs)

## Status / roadmap
MVP = Phases 0 to 2 + part of Phase 3 (details in `docs/developpment/plan-action-green-ai-proxy.md`).
Progress: _(to be kept up to date)_
- [x] Phase 0 — skeleton + local infra
- [x] Phase 1 — pass-through gateway (OpenAI ingress → Claude egress)
- [x] Phase 2 — semantic cache
- [x] Phase 3 — smart router
  - [x] 3.1 — model registry (`@ConfigurationProperties`)
  - [x] 3.2 — tier-qualified `ChatClient`s
  - [x] 3.3 — complexity classifier V1 (heuristics)
  - [x] 3.4 — `RoutingAdvisor` (classifier → ChatClient selection)
  - [x] 3.5 — classifier V2 (small model + Structured Outputs, hot-configurable rules)
- [x] Phase 4 — green inference & reporting
  - [x] 4.1 — carbon model (tokens → kWh → gCO2)
  - [x] 4.2 — real-time intensity (swappable `CarbonIntensityProvider`, ElectricityMaps)
  - [x] 4.3 — cost + carbon persistence + "avoided CO2"
  - [x] 4.4 — temporal/geo routing (async endpoint + `@Scheduled` worker + greenest zone)
  - [x] 4.5 — reporting API (aggregates + CSV/PDF export)
- [x] Phase 5 — dashboard (Svelte + Vite, mono-repo, bundled in the jar)
  - [x] 5.0 — foundation: Svelte project, `frontend-maven-plugin`, static serving, `SecurityConfig`, shell + API key + 3 KPIs
  - [x] 5.1 — API key admin: admin role + startup seed, CRUD `/v1/admin/clients`, UI (list/create/revoke)
  - [x] 5.2 — hot routing config: configurable thresholds/keywords, `/v1/admin/routing` GET/PUT, UI
  - [x] 5.3 — live metrics: time series `/v1/reports/green/series`, SVG sparklines + model mix
  - [x] 5.4 — reports: period selector + CSV/PDF download (fetch+Blob, Bearer header)
- [x] Phase 6 — engineering polish
  - [x] 6.1 — observability: Micrometer → `/actuator/prometheus`, native Spring AI metrics + custom `gatewai_*`, separate Prometheus/Grafana stack
  - [x] 6.2 — rate limiting: Bucket4j per API client, 429 + Retry-After on POST /v1/chat/completions*
  - [x] 6.3 — GraalVM native image: native-ready (parent `native` profile, reflection runtime hints tested, `docs/technical/native.md`). Full compilation to validate in a GraalVM CI
  - [x] 6.4 — MCP exposure: MCP server (Spring AI, streamable-HTTP transport `/mcp`), tools `routed_chat`/`green_report`/`carbon_intensity` via `adapter/in/mcp`, shared Bearer auth, native hints (`docs/technical/mcp.md`)
  - [x] 6.5 — final packaging: multi-stage `Dockerfile` (front+back), plug & play `docker-compose.yml` (gateway + pgvector + Ollama), `.env.example`, `.dockerignore`, README + end-to-end architecture diagram
- [x] Phase 8 — provider-agnostic egress: `gatewai.providers.<name>` instances (anthropic | openai | openai-compatible | ollama, N allowed), `EgressProviderConfiguration` factory + fail-fast validation, no fallback provider (`UnknownModelException` → 400), local-first defaults (3 Qwen tiers on Ollama, zero API keys)
- [x] Phase 9 — classifier V3 (default): semantic routes — embedding similarity (max-over-utterances, local Ollama embeddings, in-memory index) over admin-editable routes (name + tier + example prompts, bilingual defaults), `DelegatingComplexityClassifier` strategy dispatch, dashboard route editor. Future: cascade mode (see `docs/technical/routing.md` "Future work")

## Frontend build (mono-repo)
- Svelte+Vite app in `src/main/frontend`, built into `target/classes/static` (bundled in the jar).
- `./mvnw package` builds the frontend (`frontend` profile active by default); `./mvnw test` stays Node-free.
- Back-end-only work: `./mvnw … -DskipFrontend`. Frontend dev: `npm run dev` (proxies `/v1` → `:8080`).

## Communication preferences
- **After each implementation**: explain in detail what was done and why (technical choices, trade-offs, links to the architecture).
- **Before each command**: explain why the command is needed before asking for confirmation.
- **Language**: **100% English** project since 2026-06-28 — all documentation, code comments, commit messages and new artifacts are written in English. (Chat replies to the user are in English too.)
