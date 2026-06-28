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
- Honest nuance: only the Anthropic egress is wired by default; the local (Ollama)
  egress is intentionally not active out of the box (see
  [`../routing.md`](../routing.md) and the functional limitations).
