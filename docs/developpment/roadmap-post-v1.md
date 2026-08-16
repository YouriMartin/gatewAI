# Post-v1 roadmap

v1 covers Phases 0–6 (see
[`plan-action-green-ai-proxy.md`](plan-action-green-ai-proxy.md)). This is a
directional list of what would come next, derived from the honestly-documented
limitations
([`../functional/limitations.md`](../functional/limitations.md)). Nothing here is
committed; it is a backlog of credible directions, roughly grouped by theme.

Struck-through items have since shipped. Most of them came from
[`roadmap-v2.md`](roadmap-v2.md) — the v2 line of work on explainable, calibrated
decisions — and each says which batch delivered it.

## Correctness & trustworthy numbers

- **Measured carbon calibration.** Replace the placeholder per-model
  `energyIntensity` coefficients with values derived from real measurements
  (GPU profile, datacenter PUE). The carbon model
  ([`../technical/green-accounting.md`](../technical/green-accounting.md)) is built
  to accept better inputs without code changes.
- **Marginal grid intensity.** Move from average intensity (ElectricityMaps) to
  **marginal** intensity (e.g. WattTime) for load-shifting decisions — the correct
  signal per
  [`../technical/carbon-intensity-reliability.md`](../technical/carbon-intensity-reliability.md).
- **Auditable methodology.** Document Scope 2/3 boundaries and baseline assumptions
  so the CSRD exports can back audited claims rather than directional ones.

## Provider matrix

- ~~Wire the local (Ollama) egress by default~~ — **done** (Phase 8): local-first
  defaults, `gatewai.providers.<name>` instances (anthropic | openai |
  openai-compatible | ollama, N instances), no fallback provider.
- **Per-provider health checks and failover** across the configured pool (e.g.
  skip an unreachable instance and retry the tier's next registry entry).

## API surface

- ~~**Streaming.** Honor `stream: true` end to end, including a synthetic-`Flux`
  short-circuit in the cache advisor~~ — **done** (Phase 7.5): `adviseStream`
  reroutes and replays a cache hit as a synthetic chunk stream.
- **Forward more OpenAI fields** that are currently accepted but ignored (`top_p`,
  `n`, `stop`, penalties, `user`).
- **Tool/function calling** and `response_format` pass-through; additional OpenAI
  routes (`/v1/embeddings`, `/v1/models`).

## Scale & operations

- **Cluster-readiness.** Replace the in-memory pieces that assume a single node:
  a distributed rate limiter (Redis/Hazelcast-backed Bucket4j) and a **persistent
  deferred-job store** (today `InMemoryDeferredJobStore` loses jobs on restart).
- ~~**Schema migrations.** Move from `ddl-auto=update` to versioned migrations~~
  — **done** (v2 batch 0.1): Flyway owns `request_log` + `api_client`,
  `ddl-auto=validate`, see [`../technical/data-model.md`](../technical/data-model.md).
- **Native image in CI.** Validate the full GraalVM build on a dedicated runner
  (incl. OpenPDF resource hints) — see [`../technical/native.md`](../technical/native.md).

## Routing intelligence

- ~~**Cascade routing.**~~ — **done** (v2 batch 4): deterministic signals →
  embedding routes → LLM classifier, gated on the conformal prediction set
  **and** the `top1 − top2` margin band, `escalated_to` traced and the escalation
  rate metered. Opt-in (`gatewai.classifier.strategy=cascade`); `embedding`
  remains the default, and
  [`../technical/routing.md`](../technical/routing.md) publishes the measured
  cost of the escalation.
- ~~**Shared request embedding.**~~ — **done** (v2 batch 0.2): a memoizing
  `EmbeddingModel` decorator brings an uncached request from three embedding
  calls to one, without reaching around `VectorStore`
  ([ADR 0007](../technical/adr/0007-memoized-embedding-model.md)).
- **Trained classifier.** Complement the embedding/heuristic/LLM classifiers
  with a small fine-tuned model for better tier accuracy (cf. vLLM Semantic
  Router in
  [`../functional/vllm-semantic-router-comparison.md`](../functional/vllm-semantic-router-comparison.md)).
- **Feedback loop** — **half done** (v2 batch 3). Both thresholds are now fitted
  rather than guessed: a conformal quantile on labelled cases, with a stated
  guarantee, automatic staleness on a config or embedding-model change, and a
  fall back to the fixed constants
  ([`../technical/conformal-calibration.md`](../technical/conformal-calibration.md)).
  What is *not* automatic is the loop: fitting is triggered by
  `POST /v1/admin/calibration` against a **hand-labelled** set, not by observed
  production outcomes. Closing it means labelling from traffic — the decision
  rows now carry what that would need (similarity, runner-up, margin, outcome)
  — and it runs into the same exchangeability assumption the calibration rests
  on.

## Security & guardrails

- **Session-based dashboard auth** instead of holding the API key in the browser.
- **Guardrails**: PII detection and prompt-guard / jailbreak detection on the
  ingress path.
- **Tighten Actuator exposure** defaults for production.

## How to pick from this list

Bias toward items that **make the green numbers trustworthy** (calibration,
marginal intensity) and that **remove a single-instance assumption** (persistent
jobs, distributed limiter) — those unlock real deployments. Streaming and the wider
API surface are the highest-leverage adoption features.
