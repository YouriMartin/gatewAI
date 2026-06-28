# gatewAI documentation

Documentation is split into three areas. See the root [`README.md`](../README.md)
for the project overview and quick start.

| Area | Audience | Content |
|---|---|---|
| [`technical/`](technical/) | Developers, reviewers | Architecture and technical choices |
| [`functional/`](functional/) | Users, integrators | Onboarding, features, limits |
| [`developpment/`](developpment/) | Maintainer / contributors | Action plans, dev process |

## Technical ([`technical/`](technical/))

Start here: [`architecture.md`](technical/architecture.md) — the entry point that links the rest.

- [`architecture.md`](technical/architecture.md) — hexagonal layers, advisor chain, request lifecycle, ArchUnit
- [`tech-stack.md`](technical/tech-stack.md) — Java 25, Spring Boot 4, Spring AI 2.0, Postgres+pgvector, Ollama
- [`semantic-cache.md`](technical/semantic-cache.md) — the short-circuiting cache advisor
- [`routing.md`](technical/routing.md) — model registry, classifiers, routing advisor
- [`green-accounting.md`](technical/green-accounting.md) — carbon model, avoided figures, reporting
- [`carbon-aware-dispatch.md`](technical/carbon-aware-dispatch.md) — deferred, greenest-zone execution
- [`security.md`](technical/security.md) — API-key auth, hashing, rate limiting, scoped values
- [`data-model.md`](technical/data-model.md) — JPA entities, schema, vector cache
- [`mcp.md`](technical/mcp.md) — MCP server exposure (tools, transport, security, native hints)
- [`observability.md`](technical/observability.md) — Micrometer → Prometheus/Grafana metrics
- [`native.md`](technical/native.md) — GraalVM native image (profile, runtime hints, caveats)
- [`carbon-intensity-reliability.md`](technical/carbon-intensity-reliability.md) — carbon-intensity method & reliability
- [`build-and-packaging.md`](technical/build-and-packaging.md) — Maven, frontend mono-repo, Docker, native
- [`api-reference.md`](technical/api-reference.md) — REST endpoints + MCP tools
- [`testing-and-quality.md`](technical/testing-and-quality.md) — test strategy, ArchUnit, Checkstyle, SpotBugs
- [`adr/`](technical/adr/) — architecture decision records

## Functional ([`functional/`](functional/))

- [`overview.md`](functional/overview.md) — problem, value proposition, audience, vocabulary
- [`getting-started.md`](functional/getting-started.md) — deploy, get a key, first request (OpenAI + MCP), dashboard
- [`features.md`](functional/features.md) — every capability explained from a usage angle
- [`dashboard-guide.md`](functional/dashboard-guide.md) — screen-by-screen tour
- [`limitations.md`](functional/limitations.md) — owned limitations and trade-offs
- [`functional-choices.md`](functional/functional-choices.md) — functional decisions and their why
- [`vllm-semantic-router-comparison.md`](functional/vllm-semantic-router-comparison.md) — inspiration from vLLM Semantic Router and differences

## Development ([`developpment/`](developpment/))

- [`contributing.md`](developpment/contributing.md) — build, test, conventions, how to make common changes
- [`roadmap-post-v1.md`](developpment/roadmap-post-v1.md) — directional backlog after v1
- [`plan-action-green-ai-proxy.md`](developpment/plan-action-green-ai-proxy.md) — product action plan (phases 0–6)
- [`plan-action-documentation.md`](developpment/plan-action-documentation.md) — this documentation effort's action plan
