# Action Plan — Technical & Functional Documentation

> Goal: produce **ultra-detailed** documentation for gatewAI after v1, split
> across three axes. This file is a **development document** that drives the
> writing of the `technical/` and `functional/` docs. The whole project —
> including this plan — is written in **English**.

## Why this documentation

Three audiences, three intents:

| Folder | Audience | Intent | Detail level |
|---|---|---|---|
| `functional/` | Users, decision-makers, integrators | Onboard onto the app, understand its limits and the functional choices | Ultra-detailed |
| `technical/` | Developers (open-source), reviewers, recruiters | Understand the architecture and technical choices; serve as a design review and a showcase of skills | Ultra-detailed |
| `developpment/` | Maintainer / contributors | Track development (action plans, process) | Pragmatic |

## Writing conventions

- **English** across the whole project — docs, code comments, commit messages.
- **Honesty**: explicitly name the limits, the placeholders (e.g. the
  per-model energy intensities), the trade-offs and the technical debt. No
  overselling.
- **Code-anchored**: reference the real classes/files (`path:line`), quote short
  snippets. The docs must stay verifiable against the code.
- **Diagrams**: ASCII diagrams (consistent with the README); Mermaid is fine when
  the target renderer supports it.
- **No duplication**: one piece of information lives in one place. Other docs
  link to it with relative links.
- **Truth at a point in time**: date the volatile sections, and prefer
  documenting the *why* (stable) over the exact *how* (volatile).

---

## Phase D0 — Reorganize the existing docs

Put the already-written docs in their place before enriching them.

| Task | Detail |
|---|---|
| D0.1 | Move `docs/carbon-intensity-reliability.md`, `native.md`, `observability.md`, `mcp.md` → `docs/technical/` (these are technical docs) |
| D0.2 | Move `docs/plan-action-green-ai-proxy.md` → `docs/developpment/` |
| D0.3 | Update the **references**: `.claude/CLAUDE.md` (points at `plan-action-green-ai-proxy.md`), `README.md` (links to `docs/mcp.md`, `docs/`), and the cross-links between docs |
| D0.4 | Add a `docs/README.md` = navigation index for the three folders |

**Decision to confirm before D0.1/D0.2**: these moves break links; do them as one
batch with an atomic update of the references.

---

## Phase D1 — Functional documentation (`functional/`)

Help a user onboard onto the app, honestly understand its limits and the
functional choices, and position the project against vLLM Semantic Router.

| File | Content |
|---|---|
| `functional/overview.md` | The problem (cost / privacy / carbon), the value proposition, who it is for, the business vocabulary |
| `functional/getting-started.md` | End-to-end path: deploy (plug & play), get an API key, first request via the OpenAI SDK **and** via MCP, open the dashboard |
| `functional/features.md` | Features explained *from a usage angle*: semantic cache, routing, green accounting, CSRD reporting, rate limiting, key admin, MCP server |
| `functional/dashboard-guide.md` | Screen-by-screen tour (KPIs, live metrics, key admin, routing config, reports) |
| `functional/limitations.md` | **Owned limitations**: carbon estimates = placeholders to calibrate, fallible heuristic classifier, in-memory deferred-job store, single-instance, no streaming (to confirm), embedding scope, etc. |
| `functional/functional-choices.md` | Functional choices and their *why*: OpenAI ingress as the standard, Claude as the primary egress, 0.92 similarity threshold, "avoided CO2" vs a premium baseline, per-client cache namespacing… |
| `functional/vllm-semantic-router-comparison.md` | Inspirations drawn from **vLLM Semantic Router**, and our differences (green/CSRD focus, on-premise, multi-provider, thin gateway vs serving, MCP) |

**D1 acceptance criterion**: a new user deploys and makes their first request by
following only `getting-started.md`; the limits are listed without euphemism.

---

## Phase D2 — Technical documentation (`technical/`)

Architecture and technical-choice reference: design-review support, contributor
docs, and a showcase of skills.

| File | Content |
|---|---|
| `technical/architecture.md` | Hexagonal architecture (ports/adapters), Spring AI Advisor chain, ingress/egress independence, package structure, ArchUnit rules, request lifecycle (sequence diagram) |
| `technical/tech-stack.md` | Java 25 (Virtual Threads, Scoped Values — and why not Structured Concurrency), Spring Boot 4, Spring AI 2.0, PostgreSQL+pgvector, Ollama; rationale for each piece |
| `technical/semantic-cache.md` | `VectorStore` (Qdrant reversibility), `nomic-embed-text` embeddings, cosine threshold, short-circuiting advisor (low `getOrder`, no `nextCall`), client namespacing |
| `technical/routing.md` | Model registry (`@ConfigurationProperties`), V1 (heuristics) / V2 (small model + Structured Outputs) classifiers, `RoutingAdvisor`, hot config |
| `technical/green-accounting.md` | Carbon model (tokens → kWh → gCO2), swappable `CarbonIntensityProvider` (ElectricityMaps), "avoided CO2", persistence, aggregation, CSV/PDF export (CSRD) |
| `technical/carbon-aware-dispatch.md` | Deferred jobs, temporal/geographic routing, `@Scheduled` worker, greenest-zone selection |
| `technical/security.md` | API-key auth (hashing), roles/admin + seed, Bucket4j rate limiting, context propagation via Scoped Values |
| `technical/mcp-server.md` | MCP exposure (consolidates/replaces `mcp.md`): tools, transport, security, native hints |
| `technical/observability.md` | (from the existing doc) Micrometer → Prometheus/Grafana, native + `gatewai_*` metrics |
| `technical/native-image.md` | (from the existing doc) GraalVM, runtime hints, `native` profile |
| `technical/data-model.md` | JPA entities (`RequestLog`, `ApiClient`…), schema, cohabitation of the vector cache + metrics in the same Postgres |
| `technical/build-and-packaging.md` | Maven (wrapper), frontend mono-repo (frontend-maven-plugin), multi-stage Dockerfile, plug & play compose, native image |
| `technical/api-reference.md` | REST endpoints (`/v1/**`, `/v1/admin/**`, `/v1/reports/**`) + MCP tools, with request/response examples |
| `technical/testing-and-quality.md` | Test strategy, ArchUnit, Checkstyle, SpotBugs, what is unit- vs integration-tested |
| `technical/adr/` *(optional)* | Architecture Decision Records for the structuring decisions (one short file per decision) |

**D2 acceptance criterion**: an external contributor can locate any feature in the
code starting from `architecture.md`; every non-trivial choice has a documented
*why*.

---

## Phase D3 — Development documentation (`developpment/`)

Gathers the documents tied to developing the app.

| File | Content |
|---|---|
| `developpment/plan-action-green-ai-proxy.md` | Product action plan (moved from `docs/`) |
| `developpment/plan-action-documentation.md` | This plan |
| `developpment/contributing.md` *(optional)* | Contribution workflow: build, commit conventions, lint, tests before commit |
| `developpment/roadmap-post-v1.md` *(optional)* | Post-v1 directions (streaming, multi-instance, real carbon calibration, etc.) |

---

## Proposed ordering

1. **D0** — tidy the existing docs + index (`docs/README.md`): clean base.
2. **D2** — technical first (provides the factual backbone reused by the functional docs).
3. **D1** — functional (builds on the technical facts, refocused on usage).
4. **D3** — development extras (optional).

## Tracking

- [x] D0 — reorganization + index
- [x] D1 — functional documentation
- [ ] D2 — technical documentation
- [ ] D3 — development documents
