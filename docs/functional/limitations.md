# Limitations

gatewAI is a portfolio-grade project with deliberate scope. These limits are
**owned, not hidden** — read them before relying on the numbers or deploying for
real traffic. Where relevant, the technical rationale is linked.

## Carbon figures are directional, not audited

- **Per-model energy coefficients are placeholders.** The kWh-per-token values in
  the model registry are rough estimates, not measured. Absolute energy/CO2
  numbers should be read as **directional**, not exact.
- **Average vs marginal intensity.** Real-time intensity (when enabled) uses the
  grid **average**, while an *additional* load is actually served by the
  **marginal** plant. The greenest-zone *ranking* is reliable; the absolute gCO2
  is not.
- **Geography is accounting, not physical.** Carbon-aware "geo" routing chooses an
  accounting zone; it does **not** physically execute the request in another
  region. The benefit is reportable, not a real relocation. (Temporal deferral is
  real.)

Full discussion:
[`../technical/carbon-intensity-reliability.md`](../technical/carbon-intensity-reliability.md).
For audited CSRD claims you would need measured energy factors, marginal
intensity, real multi-region execution and a documented methodology.

## Egress providers: local-first by default; any mix by configuration

- Out of the box the egress is **100% local**: the three routing tiers map to
  three Qwen sizes (`qwen2.5` 0.5b/1.5b/3b) on the bundled Ollama, pulled at
  startup — the gateway needs **zero API keys**. (See
  [`../technical/routing.md`](../technical/routing.md) for the
  `DelegatingChatModel` and provider instances.)
- **Any provider mix is opt-in configuration**: declare instances under
  `gatewai.providers.<name>` (`anthropic`, `openai`, `openai-compatible` — vLLM,
  LM Studio, llama.cpp, OpenRouter, DeepSeek… — or more `ollama` servers) and
  point registry entries at them. Only instances referenced by the registry are
  built, and startup fails fast on missing keys/endpoints.
- The default local models are small (chosen for speed and pull size); swap in
  larger local models or a cloud tier for frontier quality — see the commented
  examples in `application.properties`.

## Many OpenAI request fields are accepted but ignored

The ingress DTO accepts the common OpenAI fields (`top_p`, `stream`, `n`, `stop`,
`presence_penalty`, `frequency_penalty`, `user`); `model`, `messages`,
`temperature`, `max_tokens` and **`stream`** are honored, the rest are not. In
particular:

- **Streaming is supported** (Phase 7.5): `stream: true` returns Server-Sent
  Events (`chat.completion.chunk` deltas + `[DONE]`), including a synthetic stream
  on a cache hit.
- `n`, `stop`, `top_p`, the penalties and `user` are not applied.
- No tool/function calling, no `response_format`/structured outputs on the public
  chat endpoint, no images/audio. Only `/v1/chat/completions` is implemented from
  the OpenAI surface (no `/v1/embeddings`, `/v1/models`, etc.).

## Semantic cache trade-offs

- A high-enough similarity can return a **stored answer for a prompt that only
  looks similar**, which may be wrong or stale for the new intent. Tune the
  threshold for your tolerance.
- The default **TTL is 0 (no expiry)**: cached answers are reused indefinitely
  until evicted, so time-sensitive or fast-changing content can go stale. Set a
  TTL for such workloads.
- Cache quality is bounded by the embedding model (`nomic-embed-text`).
- Not suitable as-is for strictly personalized or real-time answers unless you
  raise the threshold / set a TTL / rely on per-client namespacing.

## Complexity classifier is fallible

- The default **heuristic** classifier uses length and a finite (bilingual EN/FR)
  keyword/code-block list. It can misroute: a short but genuinely hard question may
  go `LOCAL`, and a long but trivial one may go `CLOUD_PREMIUM`.
- The **LLM** strategy is more nuanced but adds a small classification call and can
  still be wrong.
- The router optimizes for cost tier, not for guaranteed answer quality on every
  request.

## Single-instance assumptions (not cluster-ready)

- The **deferred-job store is in-memory**: queued async jobs are **lost on
  restart** and are not shared across instances.
- **Rate-limit buckets are in-memory per instance**, so the 60 req/min limit is
  per process, not cluster-wide.
- PostgreSQL (cache + metrics) is shared, but the in-memory state above means
  running multiple replicas is **not supported as-is**. Treat gatewAI as a single
  instance.

## Carbon-aware dispatch is off by default

The async endpoint and the greenest-zone worker are **disabled by default**
(`gatewai.dispatch.enabled=false`). Submitting async jobs without enabling the
worker will queue them without execution.

## Security scope

- Authentication is **API-key only** (Bearer). There is no OAuth, SSO or session
  login.
- The dashboard keeps the key **in the browser** (local storage) — acceptable for
  a self-hosted internal tool, not for a public multi-user deployment.
- `/actuator/health`, `/actuator/info` and `/actuator/prometheus` are **public** to
  ease scraping; restrict them by network/firewall in production.
- Real-time carbon (ElectricityMaps) and any provider keys require secrets you
  supply via environment variables.

## Build / validation gaps

- The **GraalVM native image** is "native-ready" but the full native compilation is
  not validated in this environment (see
  [`../technical/native.md`](../technical/native.md)); PDF export in particular may
  need extra native resource hints.

## Summary

gatewAI convincingly demonstrates the *architecture and direction* of a green LLM
gateway. The **savings logic, caching, routing and reporting are real**; the
**absolute carbon numbers are placeholders**, the **provider matrix is minimal by
default**, and the runtime assumes a **single instance**. Plan accordingly.
