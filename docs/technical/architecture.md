# Architecture

This is the entry point for the technical documentation. It explains how gatewAI
is structured, how a request flows through it, and how the architectural rules are
enforced. Per-feature deep dives are linked at the end.

## One chain, not three applications

gatewAI is a **thin gateway** built on a chain of Spring AI **Advisors** plus a
small amount of service-level logic. The three "pillars" (cache, routing, green
accounting) are not three apps — they are stages of a single processing path.

```
Client → OpenAI ingress (/v1/chat/completions)
   → ChatCompletionController → OpenAiMapper → LlmRequest (domain)
      → ChatCompletionService.complete(...)
         → LlmClient.call(...)  ── Spring AI ChatClient + advisor chain:
            → [Advisor] SemanticCache   (order HIGHEST_PRECEDENCE)
                 hit  → short-circuit, no model call
                 miss → continue, store on the way back
            → [Advisor] Routing         (order HIGHEST_PRECEDENCE + 1)
                 classify complexity → pick tier → rewrite prompt's model
            → egress: real ChatModel (any configured provider; local-first default)
         ← LlmResponse (model used, tokens, cacheHit flag)
         → green accounting (service-level) → persist RequestLog → record metrics
   ← OpenAiMapper → OpenAI DTO → Client
```

### Accurate vs conceptual

The README/diagram describe "three advisors". In the code, **two of the three
stages are Spring AI advisors** and the third is service-level:

- `SemanticCacheAdvisor` — `CallAdvisor`/`StreamAdvisor`, `getOrder()` =
  `Ordered.HIGHEST_PRECEDENCE` (runs first). Short-circuits on a hit by **not**
  calling `chain.nextCall()`.
- `RoutingAdvisor` — `getOrder()` = `HIGHEST_PRECEDENCE + 1` (runs right after the
  cache). Rewrites the prompt's target model.
- **Green accounting** lives in `ChatCompletionService` (application layer), run
  **after** `LlmClient.call(...)` returns — not as an advisor. It reads the
  response's `cacheHit` flag and token usage to compute and persist the metrics.

Both advisors are plain `@Component` beans; `SpringAiLlmClient` injects
`List<Advisor>` and wires them with `ChatClient.Builder.defaultAdvisors(...)`, so
Spring AI orders them by `getOrder()`.

## Ingress / egress independence

- **Ingress** = the format clients speak. Today: OpenAI Chat Completions
  (`adapter/in/web`) and **MCP** (`adapter/in/mcp`). Both map to the same domain
  `LlmRequest` and inbound use cases.
- **Egress** = the provider actually called, hidden behind Spring AI's
  `ChatModel`/`ChatClient`. Provider instances are declared under
  `gatewai.providers.*` and dispatched per request by `DelegatingChatModel`;
  the default is local-first (all tiers on the bundled Ollama).

The two are orthogonal: a new ingress is a new adapter calling the same `in`
ports; a new egress is a configuration change (a provider instance + a registry
entry) — see [`routing.md`](routing.md).

## Hexagonal (onion) architecture

```
io.github.yourimartin.gatewai
├── domain/
│   ├── model/            # entities, value objects, pure domain logic — no Spring/JPA/Spring AI
│   ├── port/in/          # inbound ports (use cases)
│   └── port/out/         # outbound ports (LLM, persistence, vector store, carbon…)
├── application/service/  # application services — depend on domain only
├── infrastructure/       # outbound adapters implementing the out ports
│   ├── persistence/      # JPA
│   ├── llm/              # ChatClient/ChatModel, routing, classifier, model registry
│   ├── cache/            # semantic-cache advisor
│   ├── carbon/           # carbon-intensity providers
│   ├── dispatch/         # carbon-aware deferred worker
│   └── metrics/          # Micrometer recorder
└── adapter/in/
    ├── web/              # REST controllers (OpenAI ingress, admin, reports, security)
    └── mcp/              # MCP server ingress
```

### Dependency rules (enforced)

- `domain` depends on **nothing** (no Spring, no JPA, no Spring AI).
- `application` depends on `domain` only.
- `infrastructure` implements the `out` ports of `domain`.
- `adapter.in.*` calls the `in` ports of `domain`.

These are enforced by **ArchUnit** in `ArchitectureTest` (onion-architecture
rule). Each inbound/outbound adapter package is declared there; an adapter that is
not declared cannot legally depend on the application/domain, so the test fails
fast when a layering rule is broken. See
[`testing-and-quality.md`](testing-and-quality.md).

## Request lifecycle (detailed)

1. **Auth filter** (`ApiKeyAuthenticationFilter`) validates the Bearer key, sets
   the Spring Security authentication, and binds a `RequestContext` (clientId) into
   a **Scoped Value** for the duration of the request.
2. **Rate-limit filter** (`RateLimitFilter`) checks the per-client token bucket for
   `POST /v1/chat/completions*`.
3. **Controller** maps the OpenAI DTO to a domain `LlmRequest`.
4. **`ChatCompletionService`** calls the `LlmClient` out port.
5. **`SpringAiLlmClient`** invokes the `ChatClient`, running the advisor chain
   (cache → routing → model).
6. On return, the service computes **green metrics**, persists a `RequestLog`, and
   records **Micrometer** metrics.
7. The controller maps the domain `LlmResponse` back to an OpenAI response.

The `clientId` from step 1 is read (without parameter passing) by the cache
advisor (namespacing) and the green accounting (per-client attribution) via the
`RequestContext` Scoped Value.

## Why this shape

- A **thin gateway over an advisor chain** keeps each concern isolated and
  testable, and lets the cache *block* a request natively (the advisor API is built
  for exactly this).
- **Hexagonal layering** keeps the domain pure and the providers swappable
  (`VectorStore`, `ChatModel`, `CarbonIntensityProvider` are all interfaces).
- **Scoped Values** propagate request context across the chain and virtual threads
  without threading a parameter through every method.

## Deep dives

- [`tech-stack.md`](tech-stack.md) — Java 25, Spring Boot 4, Spring AI 2.0, Postgres+pgvector, Ollama
- [`semantic-cache.md`](semantic-cache.md) — the cache advisor
- [`routing.md`](routing.md) — model registry, classifiers, routing advisor
- [`green-accounting.md`](green-accounting.md) — carbon model and reporting
- [`carbon-aware-dispatch.md`](carbon-aware-dispatch.md) — deferred, carbon-aware execution
- [`security.md`](security.md) — auth, hashing, rate limiting, scoped values
- [`data-model.md`](data-model.md) — JPA entities and schema
- [`mcp.md`](mcp.md) — MCP server ingress
- [`observability.md`](observability.md) — metrics
- [`native.md`](native.md) — GraalVM native image
- [`build-and-packaging.md`](build-and-packaging.md) — build & deploy
- [`api-reference.md`](api-reference.md) — endpoints & MCP tools
- [`testing-and-quality.md`](testing-and-quality.md) — tests, ArchUnit, linters
- [`adr/`](adr/) — architecture decision records
