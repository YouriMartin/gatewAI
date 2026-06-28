# Post-v1 roadmap

v1 covers Phases 0–6 (see
[`plan-action-green-ai-proxy.md`](plan-action-green-ai-proxy.md)). This is a
directional list of what would come next, derived from the honestly-documented
limitations
([`../functional/limitations.md`](../functional/limitations.md)). Nothing here is
committed; it is a backlog of credible directions, roughly grouped by theme.

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

- **Wire the local (Ollama) egress** by default: re-enable Ollama chat
  auto-config and add the local `ChatClient` (currently commented out in
  `ChatClientConfiguration`), so `LOCAL`-classified requests are actually served
  locally.
- **Multi-provider routing** across a real pool (OpenAI + Anthropic + local), with
  per-provider health/fallback.

## API surface

- **Streaming.** Honor `stream: true` end to end, including a synthetic-`Flux`
  short-circuit in the cache advisor (today `adviseStream` just delegates).
- **Forward more OpenAI fields** that are currently accepted but ignored (`top_p`,
  `n`, `stop`, penalties, `user`).
- **Tool/function calling** and `response_format` pass-through; additional OpenAI
  routes (`/v1/embeddings`, `/v1/models`).

## Scale & operations

- **Cluster-readiness.** Replace the in-memory pieces that assume a single node:
  a distributed rate limiter (Redis/Hazelcast-backed Bucket4j) and a **persistent
  deferred-job store** (today `InMemoryDeferredJobStore` loses jobs on restart).
- **Schema migrations.** Move from `ddl-auto=update` to versioned migrations
  (Flyway/Liquibase).
- **Native image in CI.** Validate the full GraalVM build on a dedicated runner
  (incl. OpenPDF resource hints) — see [`../technical/native.md`](../technical/native.md).

## Routing intelligence

- **Trained classifier.** Complement the heuristic/LLM classifiers with a small
  fine-tuned model for better tier accuracy (cf. vLLM Semantic Router in
  [`../functional/vllm-semantic-router-comparison.md`](../functional/vllm-semantic-router-comparison.md)).
- **Feedback loop.** Use observed outcomes (cost, latency, escalations) to tune
  thresholds automatically.

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
