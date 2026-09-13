# Green AI Proxy & Router — Project context

Open-source, self-hosted (**on-premise**) LLM proxy / gateway for enterprises:
it secures, caches, routes and measures the carbon footprint of AI requests.
A Java/Spring portfolio project, built solo.

## Stack
- **Java 25 LTS** (Virtual Threads + Scoped Values)
- **Spring Boot 4.0**, **Spring AI 2.0**
- **PostgreSQL + pgvector** — single database: vector cache **AND** relational metrics
- **In-process embeddings** — ONNX (DJL + ONNX Runtime), 384 dim, fetched at build time into the jar (v3 lot A)
- **Ollama** — local chat egress only (no longer on the decision path)
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
- Run the app: `./mvnw spring-boot:run` (Boot starts Postgres via `compose.yaml`; local chat egress is opt-in: `docker compose --profile inference up -d`)
- Infra only: `docker compose up -d`
- The embedding model is **not in git**: `download-maven-plugin` fetches it at `generate-resources` (pinned SHA-256, cached in `~/.m2`)
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
- [x] Phase 9 — classifier V3 (default): semantic routes — embedding similarity (max-over-utterances, local Ollama embeddings, in-memory index) over admin-editable routes (name + tier + example prompts, bilingual defaults), `DelegatingComplexityClassifier` strategy dispatch, dashboard route editor
- [x] v2 batch 4 — calibrated cascade routing (`strategy=cascade`, opt-in): deterministic signals → embedding routes → classifier model, escalating on the conformal set **and** the `top1−top2` margin band; `escalated_to` traced, escalation rate metered and evaluated; client pinning of registered model ids (`CLIENT_PINNED`)
- [x] v2 batch 7 — occlusion attribution (on demand): `PromptAttributionUseCase` + `OcclusionAttributionService`, segment-level contributions to the matched route's similarity, four-pass segmentation with offsets, bounded LRU cache keyed by (prompt hash, embedding model, routing config version), virtual-thread parallel embeddings. Endpoint arrives with batch 9 (`docs/technical/attribution.md`)
- [x] v2 batch 6 — decision observability: `DecisionMetricsRecorder` out port + Micrometer adapter (`gatewai_routing_decisions_total`, `_routing_margin`, `_cascade_escalations_total`, `_cache_decisions_total`, `_cache_similarity`, `_conformal_set_size`, `_routing_config_changes_total`), provisioned Grafana dashboard with the tier-mix-vs-config-edits drift panel; `gatewai_cache_hits/misses` deprecated for one release
- [x] v2 batch 8 — counterfactuals (on demand): `RouteCounterfactualUseCase` + `RouteCounterfactualService`, nearest route per *other* tier with its closest example and the similarity gap (chosen tier excluded, one route per tier), one embedding call and no cache; `SemanticRouteIndex` shared with attribution; returned examples are configuration only, asserted in a test. Endpoint arrives with batch 9 (`docs/technical/attribution.md`)
- [x] v2 batch 9 — decision API + dashboard: `DecisionHistory` out port + `JpaDecisionHistory` (merged across both decision tables), `DecisionExplanationUseCase`, `GET /v1/admin/decisions[?limit]`, `GET /v1/admin/decisions/{correlationId}` (stored rows, no recomputation), `POST /v1/admin/decisions/explain` (correlationId **or** prompt; a past decision answers `PROMPT_UNAVAILABLE` since only hashes are stored), rate-limited + admin-only + native hints; "why this decision" dashboard panel; cascade margin band now editable via `/v1/admin/routing` while staying out of `routing_config_version` (`docs/technical/decision-tracing.md`)
- [x] v2 batch 10 — documentation (closes v2): ADRs 0008 (conformal over tuning/Platt), 0009 (occlusion over gradients), 0010 (tracing cache decisions like routing); compliance note in `decision-tracing.md` (what each store holds — the vector cache **does** keep prompt text — what replays, AI Act art. 50 sourced to Reg. (EU) 2024/1689 + the Commission FAQ, evidence not compliance); `testing-and-quality.md` (549 tests, why no Testcontainers, the untested composition seam); `api-reference.md` cascade-band drift fixed; `roadmap-post-v1.md` cascade done / feedback loop half done

- [x] v3 (`docs/developpment/roadmap-v3.md`) — **all three lots done (A, B, C)**; **lot A**: in-process ONNX embedding (`paraphrase-multilingual-MiniLM-L12-v2`, int8, 384d, fetched at build time; PyTorch engine excluded; jar 161→349 MiB; boots in 7.9 s with no model server), vector schema 768→384 (upgrade = `DROP TABLE vector_store`; skipping it silently kills the cache, traced `outcome=ERROR`), model chosen on measurements (82.0 % calibrated routing vs 73.0 % EN-only and 81.0 % e5), fixtures/baselines/calibrations redone (`route-similarity-threshold` 0.60→**0.25**, q̂ 0.9526 cache / 0.2221 routing, decisions 34→**3.2 ms** p50), native hints + [ADR 0011](docs/technical/adr/0011-in-process-onnx-embedding.md).
  - **lot B — multi-instance readiness** (no Redis; state inventory + per-batch status in [`docs/technical/clustering.md`](docs/technical/clustering.md)):
    - [x] B.0 — audit: every piece of node-local state, its verdict and the batch that owns it
    - [x] B.1 — routing config persisted (`routing_config`, single row, `V6`) and propagated: `PersistentRoutingConfigPort` (`@Primary`) writes through and polls every `gatewai.routing.config-sync-interval-ms` (5 s); `application.properties` is now only the seed, edits survive restarts, `routing_config_version` identical cluster-wide. Measured on two nodes: convergence 2.05 s, one change counted per node
    - [x] B.2 — deferred jobs persisted (`deferred_job`, `V7`) and **claimed** one at a time with `FOR UPDATE SKIP LOCKED` (`JpaDeferredJobStore`); crash recovery by lease (`claimed_by` + `lease_expires_at`, swept every tick) → concurrent claims exactly-once, a lease expiry at-least-once. Every node works the queue, so the dispatch worker is deliberately **not** leader-gated. Verified on two nodes: 32 jobs / 32 executions / 0 duplicates, jobs survived a full stop, node A's job completed by node B. Gap named: prompts stored in clear text with no retention policy
    - [x] B.3 — rate limiting with two stores behind `gatewai.ratelimit.store`: `memory` (default, per process) and `postgres` (`rate_limit_bucket`, `V8`, Bucket4j `SELECT … FOR UPDATE` on the client id as a string — no advisory-lock hashing, no `SKIP LOCKED` since one client must queue on one counter). The limit + `Retry-After` are shared statics on the `RateLimiter` interface, so both stores enforce one definition; fails open. **Measured**: check costs 3.4 ms p50 / 3.8 ms p95 vs 21–24 µs in heap (`gatewai_ratelimit_check_seconds`, quantiles on by default) → <1 % of a real model call, so the token-batching optimisation was **not** built. Two nodes, limit 6/min: memory let 10/10 through, postgres 6 allowed + 4×429
    - [x] B.4 — `LeaderLock` on `pg_try_advisory_xact_lock` (**transaction**-scoped, so a node killed mid-job releases it; the work runs inside the lock's transaction), lock ids declared in a `LeaderTask` enum and namespaced by `"gatewai".hashCode()` — no schema added. Gated: decision purge + admin seeding (the latter is what makes the random-key mode produce **one** admin, not two); not gated and documented why: routing-config poll (a read, must run everywhere), dispatch worker (its own `SKIP LOCKED` claim), conformal snapshot. The `api_key_hash` unique violation is caught at the **transaction boundary** (it surfaces at commit, not at `save()`). Verified by holding the lock from psql: both nodes skipped every tick, a booting node skipped seeding and started anyway, killing the holder released it
    - [x] B.5 — **lot B closed**: `docker-compose.cluster.yml` (2 replicas + nginx round-robin + 1 Postgres, `mock` egress, nodes also on :8081/:8082) and `scripts/cluster-smoke.sh` (5 checks, PASS/FAIL, idempotent, non-zero exit). Last run: config propagated ~4 s, 12 jobs split 7/5 with each executed exactly once, 62 allowed / 8 refused on a 60/min cluster quota, 3+3 purge skips while the lock was held, 1 admin from 2 concurrent boots, `instance` tag on both. `NodeIdentity` (domain) feeds **both** `claimed_by` and the metrics tag; drift panel now `max(increase(...))` across instances. `limitations.md` rewritten: "Running more than one replica" replaces "Single-instance assumptions" 
  - **lot C — cloud carbon** (done): make the CO2 figure for **cloud** egress sourced and region-attributed. The defect it opened on is **fixed as of C.3**: `ChatCompletionService` used to resolve **one** grid intensity for every request (the gateway's own zone), so a `zone=FR` gateway booked a Claude call — US compute — at France's 56 gCO2/kWh. The energy side is **sourced and phase-split** (C.4), every row **stores its own provenance** (C.5), and ADRs 0012/0013 + the honesty pass close it (C.6). What lot C earns: location-based Scope 2 for cloud egress, from a cited method, at the grid that served the request, with self-hosted egress excluded and labelled. What it does not claim: measured — metering local inference would be a later lot D.
    - [x] C.1 — local egress **out of scope** and booked at zero (`energy-intensity=0` + `energy-source=not-accounted` on the three Ollama entries — C.4 later moved those keys under a nested `energy.*` group, so the live ones are `energy.source` etc.), `EnergySource` on `ModelDefinition` (`NOT_ACCOUNTED` | `VENDOR_PUBLISHED` | `MODELLED`, derived when omitted, fails fast if `not-accounted` carries a coefficient). `GreenReport` derives the scope from an `excludedModelMix` (cache hits are never "excluded" — no inference ran) and every renderer prints the boundary: JSON/MCP `emissions_scope` + note, CSV scope rows + `Scope exclusions` section, PDF basis bullet + "Energy accounting" column, dashboard banner + chips; tests walk each export and fail on a bare zero. ADR 0006 amended for the avoided-vs-excluded basis. Side effect of honesty: on the all-local default, avoided CO2 is zero too (the baseline is unaccounted), and the eval harness now says the carbon saving is *not determinable* instead of crediting an unmetered tier. Metering local inference is lot D
    - [x] C.2 — `region` + `region-provenance` (`KNOWN` | `ASSUMED`, default assumed) + `pue` (>= 1.0, carried not applied) on the **provider instance**: `ProviderRegion` (domain) behind two new out ports — `ProviderRegions` (reads `gatewai.providers.<name>.*`) and `CloudRegionZones` (37-region built-in table → ElectricityMaps zones, `gatewai.carbon.region-zones.*` overrides, zone-shaped input passed through, unknown region warns **once** then falls back, never throws). Zone ids verified against a live `/v3/zones` on 2026-09-13 — `BR-CS` not `BR-SE`, and `US` is an aggregate with no data tier, so the shipped assumed region is `US-MIDA-PJM`. Missing region on a cloud instance **warns**; `pue<1` **fails the context** — both checked on real boots
    - [x] C.3 — intensity resolved **per request**: `CarbonZoneResolver` (pure domain, plain values — the onion rule bars a domain model from the ports) walks dispatch zone (operator-controlled providers only) → provider region (via `CloudRegionZones`) → gateway default (a **null** zone, so `gramsCo2PerKwh()` keeps owning its value). `operatorControlled` is derived from the provider type + "not an explicitly assumed region", so a hosted OpenAI-compatible endpoint is excluded without a new property. For a hosted API the dispatch zone is **recorded, not applied** (`dispatchRecordedOnly()`). Also fixed one step removed: `GreenAccountant` now takes **two** intensities, so the avoided figure prices the premium baseline at *its* provider's grid. Verified live on a real Postgres: premium 0.0805 gCO2 (×350, was 0.0529 at ×230), deferred local 0.00312 (×30 — dispatch still applies on your own box), deferred premium 0.1015 (×350 with `chosen_zone=SE` stored)
    - [x] C.4 — energy is an `EnergyProfile` value object, not a scalar: `prefill×promptTokens/1k + decode×completionTokens/1k + fixed`, × PUE (provider's, else **1.2** — top of EcoLogits' published range), × grid. Config moved to a nested `energy.*` group (`energy.source` replaces C.1's flat key) and gained `includes-datacenter-overhead`, because a full-stack vendor figure must NOT be multiplied by PUE while a GPU-level parametric one must. Coefficients are **sourced with read-dates** (2026-09-13): `MODELLED` = EcoLogits' per-output-token fit (H100, batch 64) × the 60 GPUs its memory formula needs, prefill from 2 FLOPs/param/token at 40 % of 989 TFLOPS BF16 / 700 W, on a documented 200–600 B active-parameter range (midpoint stored, no interval propagated); `VENDOR_PUBLISHED` = Google's 0.24 Wh median Gemini prompt (arXiv:2508.15734). Mistral's LCA cited but deliberately unused (gCO2e incl. embodied, not kWh). `GreenAccountant.account` now takes two `ModelSite`s + a `TokenUsage`. Verified live: 320/322 tokens → 0.0059471552 kWh → 2.0815 gCO2, mirror-image rows differ 34×, vendor entry flat at 0.00024 kWh with its PUE correctly not applied
    - [x] C.5 — `GreenProvenance` on `RequestLog` (`V9`, **eight** nullable columns: `provider`, `grid_zone`, `grid_intensity_g_per_kwh`, `grid_zone_source`, `dispatch_zone`, `region_provenance`, `energy_source`, `pue`) so a row explains itself — `grams_co2 = energy_kwh x grid_intensity` re-derives on the row, and C.3's recorded-vs-applied dispatch zone finally lands in the data. Pre-V9 rows come back `UNKNOWN` (bucketed `unattributed`/`unknown`), never back-dated. `EmissionsBreakdown` splits gCO2 **by region and by provider** from the stored rows, flags **assumed** regions, and every surface states `SCOPE_BASIS` (location-based Scope 2 only): CSV header + sections, PDF section 5, JSON/MCP fields, dashboard panel. Deliberate limit: the coefficient set is not stored, so energy does not re-derive from tokens. Caught by reading the real PDF: the `unattributed` bucket was labelled 'known', now 'no region attributed'
    - [x] C.6 — **lot C closed**: [ADR 0012](docs/technical/adr/0012-region-on-the-provider-instance.md) (region belongs to the provider instance — not the model, which would let two entries of one connection disagree, and not the vendor response, which does not carry it; plus why a dispatch zone never overrides a hosted API) and [ADR 0013](docs/technical/adr/0013-sourced-and-labelled-not-measured.md) (sourced-and-labelled beats both measuring — impossible from here — and dropping the feature; names exactly what a later lot D would change: a `MEASURED` label for local models, not a coefficient edit). Honesty pass: `carbon-intensity-reliability.md` §5 is now a **scored** table (auditable methodology done, measured factors partly with lot D owning the rest, marginal intensity + multi-region open and post-v3), and three stale 'placeholder' claims retired (ADR 0006, `roadmap-post-v1.md`, `plan-action-documentation.md`)
    - Scope: **location-based Scope 2 only**, stated in the report header — market-based (renewable PPAs) differs by an order of magnitude and is post-v3

## Frontend build (mono-repo)
- Svelte+Vite app in `src/main/frontend`, built into `target/classes/static` (bundled in the jar).
- `./mvnw package` builds the frontend (`frontend` profile active by default); `./mvnw test` stays Node-free.
- Back-end-only work: `./mvnw … -DskipFrontend`. Frontend dev: `npm run dev` (proxies `/v1` → `:8080`).

## Communication preferences
- **After each implementation**: explain in detail what was done and why (technical choices, trade-offs, links to the architecture).
- **Before each command**: explain why the command is needed before asking for confirmation.
- **Language**: **100% English** project since 2026-06-28 — all documentation, code comments, commit messages and new artifacts are written in English. (Chat replies to the user are in English too.)
