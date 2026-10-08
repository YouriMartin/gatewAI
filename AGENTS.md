# gatewAI — agent instructions

Open-source, self-hosted (**on-premise**) LLM proxy / gateway for enterprises: it
secures, caches, routes and measures the carbon footprint of AI requests. A
Java/Spring portfolio project, built solo.

This file is read by every coding agent (Claude Code imports it from `CLAUDE.md`).
It holds **rules**, not history — the batch-by-batch log is in
[`docs/developpment/progress.md`](docs/developpment/progress.md).

## Stack
- **Java 25 LTS** (Virtual Threads + Scoped Values), **Spring Boot 4.0**, **Spring AI 2.0**
- **PostgreSQL + pgvector** — one database for the vector cache **and** relational data (Flyway, `src/main/resources/db/migration`)
- **In-process embeddings** — ONNX (DJL + ONNX Runtime), 384 dim, fetched at build time (not in git)
- **Ollama** — local chat egress only (not on the decision path)
- **Svelte + Vite** dashboard in `src/main/frontend` (see its own `AGENTS.md`)
- Build: **Maven** wrapper `./mvnw`; local infra via **Docker Compose**

## Architecture (follow strictly)
One processing chain, not three applications: a thin gateway + a Spring AI **Advisor** chain.

```
Client → OpenAI-format ingress (/v1/chat/completions) → DTO mapping → Prompt
   → [Advisor 1] semantic cache    ── short-circuits on hit (does not call chain.nextCall())
   → [Advisor 2] router            ── picks the target ChatClient
   → [Advisor 3] green accounting  ── € cost + gCO2
      → egress: real ChatModel (any configured provider mix; local-first default)
   ← remap response → OpenAI DTO → Client
```

Non-negotiable:
- **Ingress** (the OpenAI format clients speak) and **egress** (the provider called) are independent.
- Egress is **provider-agnostic and local-first**: providers are declared under
  `gatewai.providers.<name>` (anthropic | openai | openai-compatible | ollama); the model
  registry references them by name; **no fallback provider** (unknown model id → 400);
  no API key required by default.
- The cache implements `CallAdvisor`/`StreamAdvisor`, low `getOrder()`, and short-circuits by
  **not calling** `chain.nextCall()`.
- All persistence goes into the **same PostgreSQL**.
- Depend on the `VectorStore` interface, **never** on pgvector directly.
- **Structured Concurrency is preview → do not use it.** Scoped Values are fine.
- Carbon figures are **sourced and labelled, never presented as measured**; scope is
  location-based Scope 2 only (ADR 0013).

Structuring decisions are ADRs in `docs/technical/adr/` — read the relevant one before
changing what it decided.

## Hexagonal layout — enforced by ArchUnit (`ArchitectureTest`)

```
io.github.yourimartin.gatewai
├── domain/model/<category>/   # entities, value objects — no Spring, JPA or Spring AI
├── domain/port/in/            # inbound ports (use cases)
├── domain/port/out/           # outbound ports
├── application/service/       # depends on domain only
├── infrastructure/<concern>/  # outbound adapters: persistence, llm, vectorstore, cache, carbon, …
└── adapter/in/{web,mcp}/      # REST (OpenAI ingress, admin, reports) and MCP server
```

- `domain` depends on nothing; `application` on `domain` only.
- `infrastructure` implements `domain.port.out`; `adapter.in` calls `domain.port.in`.

## Commands
| Task | Command |
|---|---|
| Tests (Node-free) | `./mvnw test` — add `-DskipFrontend` on any goal that would build the UI |
| Full gate (Checkstyle + tests + SpotBugs) | `./mvnw -DskipFrontend verify` |
| One test class | `./mvnw -DskipFrontend test -Dtest=FooTest` |
| Run the app | `./mvnw spring-boot:run` (Boot starts Postgres via `compose.yaml`) |
| Local chat egress | `docker compose --profile inference up -d` |
| Two-node cluster check | `docker compose -f docker-compose.cluster.yml up -d` then `scripts/cluster-smoke.sh` |

**Run `./mvnw test` before every commit that touches `src/` or `pom.xml`.**

## Conventions
- Java `record` for DTOs; Spring AI 2.0 immutable builders (no setters).
- **Jackson 3**: `tools.jackson.*`. Only the annotations stay in `com.fasterxml.jackson.annotation`.
- Secrets via environment variables only, never committed.
- New native-reflected types need a runtime hint (see `adapter/in/web/nativehints`, `docs/technical/native.md`).
- Schema change = a new `V<n>__*.sql` migration; never edit an applied one.

## Tests & static analysis
- `{Class}Test.java` in the mirror package under `src/test/java`.
- Unit-test every class **except** REST controllers (integration-tested) and trivial mappers.
- Checkstyle (`checkstyle.xml`, Google style + overrides) fails at `validate`;
  SpotBugs (effort max, threshold low) at `verify`.

## Documentation
- **English only** (code, comments, docs, commits). The folder name `docs/developpment/` is
  intentionally spelled that way — do not "fix" it.
- Docs map: [`docs/README.md`](docs/README.md). Doc-writing rules: [`docs/AGENTS.md`](docs/AGENTS.md).
- When a batch closes: add it to `docs/developpment/progress.md`, update the status line below,
  and record any deviation from the plan in `docs/decisions.md`.

## Working agreements
- After an implementation: explain what was done and why (choices, trade-offs, link to the architecture).
- Before running a command that needs confirmation: say why it is needed.
- End every implementation with a proposed commit message: short, English, imperative mood.

## Status
v4 in progress — lot A done (A.1–A.3), B.1 done; next: batch B.2 (docs/developpment/roadmap-v4.md)
