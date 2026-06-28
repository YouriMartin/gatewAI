# ADR 0003 — Separate ingress from egress

**Status:** Accepted

## Context

Clients need a stable API to call; the gateway needs freedom to choose which
provider answers. Coupling the two would make provider changes break clients.

## Decision

Treat **ingress** (the format clients speak) and **egress** (the provider called)
as **orthogonal**. Ingress is the **OpenAI Chat Completions** format (plus an
**MCP** ingress); egress is hidden behind Spring AI's `ChatModel`/`ChatClient`,
defaulting to Anthropic Claude. A request's `model` field is a **hint** the router
may override.

## Consequences

- Any existing OpenAI SDK works by changing only the `base_url` — a major adoption
  argument.
- A new ingress is a new inbound adapter calling the same `in` ports (this is how
  MCP was added without touching business logic).
- A new egress is a starter + bean behind the `LlmClient` out port.
- Multi-provider egress (Phase 7.2): a `@Primary` `DelegatingChatModel` dispatches
  per request to the Anthropic or Ollama `ChatModel` based on the routed model id's
  provider — so cloud tiers hit Claude and the `LOCAL` tier hits a local Ollama
  model, both behind one advisor chain (see [`../routing.md`](../routing.md)).
