# Features

This page explains each capability **from a usage angle** — what it does, how it
behaves, and how you control it. For the implementation, follow the links into
[`../technical/`](../technical/).

## OpenAI-compatible gateway

gatewAI exposes `POST /v1/chat/completions` in the OpenAI Chat Completions format.
Any OpenAI client works by changing the `base_url` to `http://<host>:8080/v1` and
using a gatewAI API key. The **ingress** (OpenAI format) and the **egress** (the
provider actually called) are independent: you can switch or add providers without
touching client code.

- The `model` you pass is a **hint**; the router may override it (see Routing).
- Responses are OpenAI-shaped, including `usage` token counts. The `model` field
  reports the model that actually served the request.

## Semantic cache

Before any model is called, gatewAI embeds the incoming prompt and looks for a
**semantically similar** prompt already answered. If one is close enough, the
stored answer is returned immediately — **no model call, no provider cost, near-zero
latency**. This catches not only exact repeats but rephrasings.

Behaviour you can rely on / control:

- **Similarity threshold** — how close counts as a hit (default `0.92`, cosine).
  Higher = stricter (fewer but safer hits); lower = more aggressive reuse.
- **Per-client namespacing** (default on) — one client's cached answers are not
  served to another, so tenants stay isolated.
- **TTL** — optional freshness window in minutes. Default `0` means **no
  expiry** (entries are reused indefinitely); set a positive value to expire old
  answers.

When to be careful: caching assumes that a similar prompt deserves the same
answer. For time-sensitive or strictly personalized prompts, raise the threshold
or set a TTL. Details: [`../technical/` semantic cache] and
[`limitations.md`](limitations.md).

## Smart routing

On a cache miss, the **router** classifies the request's complexity and sends it
to the cheapest **tier** that can handle it:

- `LOCAL` — trivial requests (greetings, short rewrites) a small local model can
  serve.
- `CLOUD_ENTRY` — moderately complex requests needing a cheap cloud model.
- `CLOUD_PREMIUM` — complex requests (architecture, debugging, algorithms,
  security analysis, long reasoning) needing a premium model.

Two classification strategies are available:

- **Heuristic** (default) — zero cost, zero latency. Uses text length and
  keyword/code-block detection. Routes to premium on signals like code blocks,
  long text (> 500 chars by default), or keywords such as *refactor*,
  *architecture*, *analyze*, *debug*, *security* (bilingual EN/FR keyword list).
- **LLM** — a small/cheap model returns a structured tier label. More nuanced, at
  the cost of a small classification call.

The thresholds, keywords and strategy are **hot-configurable** at runtime (see
Routing configuration below) — no restart.

## Green accounting

Every served request is accounted for: tokens → estimated kWh (a per-model energy
coefficient) → gCO2 (using the grid carbon intensity). gatewAI also computes the
**avoided** figures: what a premium-by-default call *would* have cost and emitted
versus what actually happened after cache + routing.

Each request is persisted with its cost, energy and emissions, so the reporting
API and dashboard can aggregate them.

> **Honesty note:** the per-model energy coefficients are **placeholders** and the
> geographic figures are *accounting*, not physical relocation. Treat the absolute
> carbon numbers as directional. Full discussion:
> [`../technical/carbon-intensity-reliability.md`](../technical/carbon-intensity-reliability.md)
> and [`limitations.md`](limitations.md).

## Carbon-aware deferred execution (optional)

For non-interactive workloads, a request can be **submitted asynchronously**
(`POST /v1/chat/completions/async`, returns `202` + a job id) and executed later by
a worker that picks the moment/zone with the lowest carbon intensity. Poll the
result at `GET /v1/chat/completions/async/{id}`.

This is **disabled by default** (`gatewai.dispatch.enabled=false`): the endpoint
queues jobs but the worker only runs when enabled.

## Reporting & CSRD export

The reporting API aggregates the green figures over a date range:

- `GET /v1/reports/green?from=…&to=…` — totals: requests, cache hits and hit rate,
  € cost, € avoided, energy (kWh), gCO2, gCO2 avoided, and the per-model request
  mix. Add `&format=csv` or `&format=pdf` to download a CSRD-friendly file.
- `GET /v1/reports/green/series?from=…&to=…` — the same figures bucketed **per
  day**, for charts.

`from`/`to` are ISO-8601 instants (e.g. `2026-01-01T00:00:00Z`). The CSV/PDF
exports are meant to feed CSR / CSRD disclosures.

## API key administration

Access is controlled by **API keys** mapped to **clients**. There are two roles:
regular and **admin**. Admin-only endpoints manage clients:

- `POST /v1/admin/clients` — create a client; the response returns the new key
  **once**.
- `GET /v1/admin/clients` — list clients (status, role).
- `POST /v1/admin/clients/{id}/revoke` — revoke a client's key.

Keys are stored only as **hashes** (the raw key is shown once at creation). A
bootstrap admin client is created at first start and its key is logged once — see
[`getting-started.md`](getting-started.md).

## Live routing configuration

Admins can read and change the routing rules **at runtime**:

- `GET /v1/admin/routing` — current strategy, length thresholds and premium
  keywords.
- `PUT /v1/admin/routing` — update them. Changes take effect immediately, no
  restart.

The dashboard exposes this as a form.

## Rate limiting

Each API client is rate-limited on chat completions to protect the gateway and the
upstream providers. The default is **60 requests/minute per client** on
`POST /v1/chat/completions` and the async submit endpoint (status polls, admin and
report calls are not limited). Over the limit returns **HTTP 429** with a
`Retry-After` header. It is enabled by default and configurable.

## MCP server

gatewAI is also a **Model Context Protocol** server at `/mcp`, so MCP-capable
assistants can drive it. Exposed tools:

- `routed_chat` — complete a prompt through the gateway (cache + routing +
  accounting); returns the answer, the model actually used, the cache-hit flag and
  token usage.
- `green_report` — aggregated cost and carbon for a date range.
- `carbon_intensity` — current grid carbon intensity for a zone.

It uses the same Bearer API-key auth as `/v1/**`. Details:
[`../technical/mcp.md`](../technical/mcp.md).

## Observability

Operational metrics are exported for Prometheus at `/actuator/prometheus`
(`gatewai_*` counters and timers for requests, tokens, cost, energy, CO2, cache
hits/misses, latency), plus native Spring AI model metrics. A ready-made
Prometheus + Grafana stack is provided. Details:
[`../technical/observability.md`](../technical/observability.md).
