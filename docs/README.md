# gatewAI documentation

## Using gatewAI

The short path, in the order you need it:

1. **Quickstart** — [`getting-started.md`](functional/getting-started.md): deploy, get a
   key, send a first request (OpenAI SDK, a chat UI, MCP).
2. **Configuration reference** — providers, the model registry, routing, failover and
   pricing: [`routing.md` → Configuration reference](technical/routing.md#configuration-reference).
   Every other property is commented where it is defined, in
   [`application.properties`](../src/main/resources/application.properties).
3. **Client compatibility** — which clients were checked and how:
   [`features.md` → Client compatibility](functional/features.md#client-compatibility);
   what a request may carry: [`api-reference.md`](technical/api-reference.md#chat-completions-openai-compatible).
4. **Troubleshooting** — [`troubleshooting.md`](functional/troubleshooting.md): every
   error a client can get, and how to find a request by its `X-Request-Id`.

Before relying on the numbers: [`limitations.md`](functional/limitations.md).

## Everything else

Documentation is split into three areas. See the root [`README.md`](../README.md)
for the project overview.

| Area | Audience | Content |
|---|---|---|
| [`technical/`](technical/) | Developers, reviewers | Architecture and technical choices |
| [`functional/`](functional/) | Users, integrators | Onboarding, features, limits |
| [`developpment/`](developpment/) | Maintainer / contributors | Action plans, dev process |

[`decisions.md`](decisions.md) records implementation deviations — where the
code departs from the plan it belongs to, and why.

### Technical ([`technical/`](technical/))

Start here: [`architecture.md`](technical/architecture.md) — the entry point that links the rest.

- [`architecture.md`](technical/architecture.md) — hexagonal layers, advisor chain, request lifecycle, ArchUnit
- [`tech-stack.md`](technical/tech-stack.md) — Java 25, Spring Boot 4, Spring AI 2.0, Postgres+pgvector, in-process ONNX embeddings, Ollama egress
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
- [`decision-tracing.md`](technical/decision-tracing.md) — what is recorded per decision, versioning, what replays
- [`conformal-calibration.md`](technical/conformal-calibration.md) — calibrated cache and routing thresholds
- [`attribution.md`](technical/attribution.md) — occlusion attribution and counterfactuals
- [`evaluation.md`](technical/evaluation.md) — labelled datasets, harness, quality metrics
- [`api-reference.md`](technical/api-reference.md) — REST endpoints + MCP tools
- [`testing-and-quality.md`](technical/testing-and-quality.md) — test strategy, ArchUnit, Checkstyle, SpotBugs
- [`clustering.md`](technical/clustering.md) — what is shared and what is node-local across replicas
- [`adr/`](technical/adr/) — architecture decision records

### Functional ([`functional/`](functional/))

- [`overview.md`](functional/overview.md) — problem, value proposition, audience, vocabulary
- [`getting-started.md`](functional/getting-started.md) — deploy, get a key, first request (OpenAI + MCP), dashboard
- [`troubleshooting.md`](functional/troubleshooting.md) — every client-facing error, what to do, and finding a request by `X-Request-Id`
- [`features.md`](functional/features.md) — every capability explained from a usage angle
- [`dashboard-guide.md`](functional/dashboard-guide.md) — screen-by-screen tour
- [`limitations.md`](functional/limitations.md) — owned limitations and trade-offs
- [`functional-choices.md`](functional/functional-choices.md) — functional decisions and their why
- [`vllm-semantic-router-comparison.md`](functional/vllm-semantic-router-comparison.md) — inspiration from vLLM Semantic Router and differences

### Development ([`developpment/`](developpment/))

- [`contributing.md`](developpment/contributing.md) — build, test, conventions, how to make common changes
- [`releasing.md`](developpment/releasing.md) — cutting a release: tag, image, attestation, what each step checks
- [`roadmap-v2.md`](developpment/roadmap-v2.md) — v2 action plan: explainable, calibrated decisions (done)
- [`roadmap-v3.md`](developpment/roadmap-v3.md) — v3 action plan: in-process ONNX embedding, multi-instance readiness, cloud carbon (done)
- [`roadmap-v4.md`](developpment/roadmap-v4.md) — v4 action plan: from portfolio to a tool people use (in progress)
- [`progress.md`](developpment/progress.md) — batch-by-batch log of what shipped (moved out of the agent instructions)
- [`roadmap-post-v1.md`](developpment/roadmap-post-v1.md) — directional backlog after v1
- [`plan-action-green-ai-proxy.md`](developpment/plan-action-green-ai-proxy.md) — product action plan (phases 0–6)
- [`plan-action-documentation.md`](developpment/plan-action-documentation.md) — this documentation effort's action plan
