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
- **Troubleshooting guide** listing every client-facing error and how to find a
  request by its `X-Request-Id`.

### Changed

- **One name: gatewAI.** The dashboard, its HTML title and the MCP server no longer
  carry the project's former name. The MCP server reports itself as `gatewai` (was
  `gatewai-green-proxy`) with the jar's version (was a fixed `0.1.0`).

### Upgrading from a source build

- **The database name does not change.** The compose stacks that keep data in a
  volume (`docker-compose.yml`, `compose.yaml`) still create and use `greenaiproxy`,
  because `POSTGRES_DB` only applies when a volume is first initialised: renaming the
  default would point an existing install at a database that does not exist. Only
  the volume-less stacks (the cluster demo, CI) now use `gatewai`. Nothing to do.
- **MCP clients** key their configuration by the name you gave the server, so the
  server's rename needs no change on their side.
