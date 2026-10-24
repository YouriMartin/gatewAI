# Changelog

All notable changes to gatewAI are recorded here. The format follows
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0.0, a minor
version may break configuration or the API; the entry says how to upgrade.

The release workflow publishes the section of the tagged version as the GitHub
release notes, and refuses a final tag that has none — see
[`docs/developpment/releasing.md`](docs/developpment/releasing.md).

## [Unreleased]

The first published release: everything below is new to anyone pulling an image.

### Added

- **OpenAI-compatible ingress**: `POST /v1/chat/completions` (streaming and not),
  `GET /v1/models`, an async endpoint for carbon-aware deferred jobs, and an MCP
  server on `/mcp`. Content as a string, text parts or `null`; `developer`, `tool`
  and `function` messages; `max_completion_tokens`, `top_p`, penalties, `seed`,
  `stream_options.include_usage`.
- **Provider-agnostic, local-first egress**: `anthropic`, `openai`,
  `openai-compatible` and `ollama` instances under `gatewai.providers.<name>`, no API
  key required by default, and an unknown model id is a 400, never a fallback.
- **Semantic cache scoped by conversation context** (ADR 0014): similarity on the
  last user turn only inside an identical scope (history, pinned model, `user`,
  `stop`); in-process ONNX embeddings, so no model server is needed to decide.
- **Routing** by semantic routes, an optional calibrated cascade, and
  conversation-sticky model choice (ADR 0015); every cache and routing decision
  traced, explained and exposed in the admin API and the dashboard.
- **Pass-through** for requests the chain cannot honour — tools, non-text parts,
  `response_format`, `n > 1`… — forwarded as written to one registered model,
  streamed or not, still rate-limited, traced and accounted (ADR 0016).
- **Ordered failover inside a tier** on connection failure, timeout, 429 or 5xx
  before the first byte, traced as `failover_event` (ADR 0017).
- **Cost and carbon accounting**: split input, output and cached-input pricing in an
  explicit currency; location-based Scope 2 carbon figures, sourced and labelled,
  never presented as measured (ADR 0013).
- **Operations**: per-client API keys and rate limits, Prometheus metrics, a Svelte
  dashboard bundled in the jar, and multi-replica operation on one PostgreSQL.
- **Release pipeline**: a multi-arch image (`linux/amd64`, `linux/arm64`) on
  `ghcr.io/yourimartin/gatewai` with an SBOM and a verifiable build-provenance
  attestation; the version is stamped from the tag and served on `/actuator/info`.
