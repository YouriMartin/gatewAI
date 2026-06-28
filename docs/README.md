# gatewAI documentation

Documentation is split into three areas. See the root [`README.md`](../README.md)
for the project overview and quick start.

| Area | Audience | Content |
|---|---|---|
| [`technical/`](technical/) | Developers, reviewers | Architecture and technical choices |
| [`functional/`](functional/) | Users, integrators | Onboarding, features, limits |
| [`developpment/`](developpment/) | Maintainer / contributors | Action plans, dev process |

## Technical ([`technical/`](technical/))

- [`mcp.md`](technical/mcp.md) — MCP server exposure (tools, transport, security, native hints)
- [`observability.md`](technical/observability.md) — Micrometer → Prometheus/Grafana metrics
- [`native.md`](technical/native.md) — GraalVM native image (profile, runtime hints, caveats)
- [`carbon-intensity-reliability.md`](technical/carbon-intensity-reliability.md) — carbon-intensity method & reliability

> More technical docs (architecture, tech stack, semantic cache, routing, green
> accounting, security, data model, packaging, API reference, testing) are
> planned — see the documentation action plan.

## Functional ([`functional/`](functional/))

- [`overview.md`](functional/overview.md) — problem, value proposition, audience, vocabulary
- [`getting-started.md`](functional/getting-started.md) — deploy, get a key, first request (OpenAI + MCP), dashboard
- [`features.md`](functional/features.md) — every capability explained from a usage angle
- [`dashboard-guide.md`](functional/dashboard-guide.md) — screen-by-screen tour
- [`limitations.md`](functional/limitations.md) — owned limitations and trade-offs
- [`functional-choices.md`](functional/functional-choices.md) — functional decisions and their why
- [`vllm-semantic-router-comparison.md`](functional/vllm-semantic-router-comparison.md) — inspiration from vLLM Semantic Router and differences

## Development ([`developpment/`](developpment/))

- [`plan-action-green-ai-proxy.md`](developpment/plan-action-green-ai-proxy.md) — product action plan (phases 0–6)
- [`plan-action-documentation.md`](developpment/plan-action-documentation.md) — this documentation effort's action plan
