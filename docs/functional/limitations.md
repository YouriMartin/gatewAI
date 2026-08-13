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
- **Measured (v2 batch 5)**: on 300 hand-labelled `(query, cached entry)` pairs,
  the shipped 0.92 threshold wrongly serves **16 %** of the answers it should
  have refused and wrongly refuses **46 %** of those it could have served. No
  single threshold makes both small — the two distributions overlap. A residual
  ~4 % of false positives is irreducible by **any** threshold: they are questions
  whose answer changed since it was cached, a freshness problem a TTL fixes and
  similarity cannot.
- **Calibrated (v2 batch 3)**, the threshold is fitted rather than guessed and
  the wrong-answer rate drops to **12.5 %** — but the hit rate drops with it
  (33 % → 22 %), so fewer wrong answers is bought with more model calls. The
  guarantee is **marginal, not per-request**: "at most α of non-servable pairs
  are served" describes the population, never your request. It also assumes the
  calibration cases are exchangeable with production traffic; the shipped labels
  are deliberately adversarial, so the measured rates read as a worst case and
  the promise transfers only as far as that resemblance holds. See
  [`evaluation.md`](../technical/evaluation.md) and
  [`conformal-calibration.md`](../technical/conformal-calibration.md).
- Not suitable as-is for strictly personalized or real-time answers unless you
  raise the threshold / set a TTL / rely on per-client namespacing.

## Complexity classifier is fallible

- The default **embedding** classifier is only as good as its route examples
  and its embedding model: `nomic-embed-text` is English-centric, so
  non-EN/FR languages need their own examples (or a multilingual embedding
  model) to match reliably. Requests unlike any example fall back to the
  heuristic.
- The **heuristic** classifier uses length and a finite (bilingual EN/FR)
  keyword/code-block list. It can misroute: a short but genuinely hard question may
  go `LOCAL`, and a long but trivial one may go `CLOUD_PREMIUM`.
- The **LLM** strategy is more nuanced but adds a small classification call and can
  still be wrong.
- The router optimizes for cost tier, not for guaranteed answer quality on every
  request.
- **Measured (v2 batch 5)**, on 300 hand-labelled prompts: at the guessed 0.60
  threshold the default embedding strategy picks the labelled tier **62 %** of
  the time (the heuristic alone: 34 %). Its errors were overwhelmingly
  *under*-routing, because prompts scoring below the threshold fell back to the
  heuristic, which sends short text to `LOCAL`.
- **Calibrated (v2 batch 3)**, the threshold is fitted on labelled data and
  accuracy reaches **83 %**, with under-routing down from 34 to 8 cases in 100.
  The reported carbon saving *fell* as a result (55 % → 40 %): the earlier figure
  was partly answer quality given away rather than efficiency gained, which is
  why savings and under-routing are printed together. Two caveats remain: the
  guarantee is marginal rather than per-request, and French accuracy dips
  slightly (80 % → 76 %) as the lower bar admits more near-misses. See
  [`evaluation.md`](../technical/evaluation.md) and
  [`conformal-calibration.md`](../technical/conformal-calibration.md).
- **The cascade (v2 batch 4) is opt-in, and its benefit is not measured.** It
  escalates 23 % of requests to the classifier model and those requests hold
  61 % of the routing errors, so the gate targets the right ones. Whether the
  model *fixes* them cannot be measured hermetically: the harness has no model
  server, and with level 3 stubbed by the heuristic the cascade scores 6 points
  below the routes alone. Turn it on only where the classifier model is better
  than the heuristic on hard prompts, and watch
  `gatewai_cascade_escalations_total`.
- **A client can bypass routing.** Naming a registered model id pins it (v2
  batch 4), which is the point — the gateway is also a plain proxy — but it means
  the green router only governs traffic that does not pin. Set
  `gatewai.classifier.client-pinning=false` to make routing mandatory.

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
