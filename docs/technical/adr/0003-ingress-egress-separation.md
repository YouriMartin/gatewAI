# ADR 0003 — Separate ingress from egress

**Status:** Accepted

## Context

Clients need a stable API to call; the gateway needs freedom to choose which
provider answers. Coupling the two would make provider changes break clients.

## Decision

Treat **ingress** (the format clients speak) and **egress** (the provider called)
as **orthogonal**. Ingress is the **OpenAI Chat Completions** format (plus an
**MCP** ingress); egress is hidden behind Spring AI's `ChatModel`/`ChatClient`.
A request's `model` field is a **hint** the router may override.

Egress has **no privileged vendor**. Phase 8 generalised this to N provider
instances declared under `gatewai.providers.<name>` (`anthropic` | `openai` |
`openai-compatible` | `ollama`), referenced by the model registry, with
**local-first defaults**: three Qwen sizes on the bundled Ollama and zero API
keys. There is no fallback provider — an unregistered model id is a 400.

## Consequences

- Any existing OpenAI SDK works by changing only the `base_url` — a major adoption
  argument.
- A new ingress is a new inbound adapter calling the same `in` ports (this is how
  MCP was added without touching business logic).
- A new egress is a starter + bean behind the `LlmClient` out port.
- Multi-provider egress (Phase 7.2, generalised in Phase 8): a `@Primary`
  `DelegatingChatModel` resolves the routed model id in the registry and
  dispatches to *that entry's* provider instance — any mix of Ollama, vLLM,
  Anthropic, OpenAI — all behind one advisor chain (see
  [`../routing.md`](../routing.md)).
- The separation held under a change it was never written for: v3 lot A moved the
  **embedding** model in-process while leaving egress untouched
  ([ADR 0011](0011-in-process-onnx-embedding.md)). Embeddings were never part of
  egress, and that is why the decision path could stop depending on a model
  server without a single advisor changing.
