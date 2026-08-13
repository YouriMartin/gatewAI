# Observability (Phase 6.1, extended in v2 batch 6)

Micrometer metrics exported in Prometheus format, on three levels: Spring AI's
native model observations, the per-request green metrics, and — since v2
batch 6 — the **decisions** behind them.

## Endpoints (Actuator)

| Endpoint | Access | Content |
|---|---|---|
| `/actuator/health` | public | health |
| `/actuator/info` | public | build info |
| `/actuator/prometheus` | public | metrics in Prometheus format |
| `/actuator/metrics` | authenticated | metrics as JSON (exploration) |

> `/actuator/prometheus` is open to make internal scraping easy; in production,
> restrict it by network/firewall.

## Native Spring AI metrics

With Actuator + Micrometer on the classpath, Spring AI automatically instruments
model calls (latency, model, usage tokens) via the `ObservationRegistry` — no
code to write.

## Custom green metrics (`gatewai_*`)

Emitted by `MicrometerMetricsRecorder` on every served request:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `gatewai_requests_total` | counter | `model`, `cache_hit` | request count |
| `gatewai_tokens_total` | counter | `model` | tokens consumed |
| `gatewai_cost_eur_total` | counter | `model` | actual € cost |
| `gatewai_cost_avoided_eur_total` | counter | — | € saved |
| `gatewai_energy_kwh_total` | counter | `model` | estimated energy |
| `gatewai_co2_grams_total` | counter | `model` | gCO2 emitted |
| `gatewai_co2_avoided_grams_total` | counter | — | gCO2 avoided |
| `gatewai_cache_hits_total` / `gatewai_cache_misses_total` | counter | — | cache |
| `gatewai_request_latency_seconds` | timer (histogram) | `model` | latency |
| `gatewai_decisions_write_failures_total` | counter | `kind` (`routing`/`cache`) | decision rows that could not be persisted (v2 batch 2) |
| `gatewai_conformal_calibration_stale` | gauge | `target` (`cache`/`routing`) | 1 when the fixed threshold is in force instead of a calibration (v2 batch 3) |
| `gatewai_conformal_threshold` | gauge | `target` | the similarity threshold actually applied |

`gatewai_conformal_calibration_stale` is the same idea applied to calibration:
falling back to a fixed threshold is invisible in the responses by construction,
so one alert rule (`== 1`) covers never-calibrated, gone-stale and switched-off
alike. The *reason* is deliberately not a tag — it changes over the life of a
process, and a gauge whose tags move creates a new series every time. Read it
from `GET /v1/admin/calibration` or the startup log, which states both
thresholds and where they came from. `gatewai_conformal_threshold` beside it
makes a threshold change visible on the same graph as the tier mix it moved.

The failure counter is what keeps best-effort tracing honest: decisions are
written off the request path and never fail a completion, so a store that is
down would otherwise degrade in complete silence. A non-zero rate means the
trace has holes, not that requests are failing.

Common tag `application=gatewai` on every series.

## Decision metrics (v2 batch 6)

Emitted by `MicrometerDecisionMetricsRecorder` from the **same objects** that go
into `routing_decision` and `cache_decision`, so a series and the row behind it
can never disagree — one is the aggregate of the other, not a second
measurement of it.

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `gatewai_routing_decisions_total` | counter | `tier`, `reason`, `strategy` | every routing decision, by what it chose and why |
| `gatewai_routing_margin` | summary (p50/p95) | `tier` | the winning route's lead over the runner-up |
| `gatewai_cascade_escalations_total` | counter | `to_level` (`deterministic`/`embedding`/`llm`) | how far the cascade went; absent when the cascade is off |
| `gatewai_conformal_set_size` | summary (p50/p95) | `target` (`routing`) | tiers inside the calibrated prediction set |
| `gatewai_cache_decisions_total` | counter | `outcome`, `conformal_status` | every cache decision, and the shape of the set behind it |
| `gatewai_cache_similarity` | summary (p50/p95) | — | best candidate's similarity |
| `gatewai_routing_config_changes_total` | counter | — | edits to the live routing rules |
| `gatewai_decisions_metric_failures_total` | counter | `kind` | decisions that could not be counted |

Three rules this table follows, and one deviation from the plan:

- **Names are registered dotted** (`gatewai.routing.decisions`); the underscored
  form above is Micrometer's Prometheus rendering, not the registered name.
- **Tags are bounded**: every tag value is an enum constant, so the series count
  is a product of small numbers and stays constant over a process's life. The
  prompt, the model id and the correlation id are never tags — the first two
  would leak content and cardinality, the third is what the decision tables are
  for. Metrics answer "how often", rows answer "why this one".
- **Continuous quantities are summaries, never tags.** A similarity of 0.9431 as
  a label would mint one series per request.
- **`gatewai_conformal_set_size` is routing-only** (D27). The cache's set is over
  candidate documents and its size beyond one is not recorded; `conformal_status`
  on `gatewai_cache_decisions_total` carries the shape (`empty_set` /
  `singleton` / `ambiguous` / `not_calibrated` / `stale_calibration`) without
  inventing a number nothing measured.

### Deprecated: `gatewai_cache_hits_total` / `gatewai_cache_misses_total`

Superseded by `gatewai_cache_decisions_total{outcome}`, which distinguishes what
the old pair could not: a `BYPASS` (nothing was looked up), an `ERROR` (the
lookup failed) and, with `conformal_status="ambiguous"`, a **deliberate refusal**
to serve. All three used to be "a miss".

Both are emitted **for one release** so existing dashboards and alerts keep
working; the bundled Grafana dashboard already uses the new one. Migration:

```promql
# before
gatewai_cache_hits_total / (gatewai_cache_hits_total + gatewai_cache_misses_total)
# after
sum(rate(gatewai_cache_decisions_total{outcome="hit"}[5m]))
  / sum(rate(gatewai_cache_decisions_total{outcome=~"hit|miss"}[5m]))
```

### Tracing spans

OTel span attributes (`chosen_tier`, `decision_reason`, `effective_strategy`, a
**bucketed** margin) stay deferred: they need a tracing dependency the project
has not taken (D2), and the correlation id already joins a request to its
decision rows. The metrics above answer the aggregate questions; the tables
answer the per-request ones.

## Prometheus + Grafana demo

The app manages its own `compose.yaml` (Postgres + Ollama). The observability
stack is **separate** and started on demand:

```bash
# 1. start the app (exposes :8080)
./mvnw spring-boot:run
# 2. start Prometheus + Grafana
docker compose -f docker-compose.observability.yml up -d
```

- Prometheus: http://localhost:9090 (scrapes `gatewai` every 15 s)
- Grafana: http://localhost:3000 (anonymous admin)

Grafana is **provisioned from the repo** (v2 batch 6): the Prometheus data
source (`docker/grafana/provisioning/`) and the dashboard
**gatewAI — decisions, confidence and footprint**
(`docker/grafana/dashboards/gatewai-decisions.json`) are there on first start.
The dashboard is a committed file rather than something rebuilt by hand in the
UI, so a panel change is reviewable in a diff.

Its four rows: routing decisions (tier mix and reasons), confidence (margins,
prediction-set size, escalation rate), the semantic cache (outcomes, similarity
against the threshold in force) and calibration + footprint.

### The drift panel

The one panel worth explaining. It draws the **tier mix as a share** with
**routing-config edits** overlaid as bars:

```promql
sum by (tier) (rate(gatewai_routing_decisions_total[$__rate_interval]))
  / ignoring(tier) group_left sum(rate(gatewai_routing_decisions_total[$__rate_interval]))
increase(gatewai_routing_config_changes_total[$__rate_interval])
```

A mix that moves **at** an edit is the edit — someone changed a threshold or a
route. A mix that moves while the edit series stays flat is the **incoming
traffic** drifting, which no amount of re-reading the configuration will
explain. That distinction is the reason `gatewai_routing_config_changes_total`
exists: a log line cannot be graphed next to the mix, a counter can. Read it
beside the margin panel — decisions holding steady while margins collapse is the
same signal, earlier.

Other PromQL examples:

```promql
sum(rate(gatewai_co2_avoided_grams_total[5m]))            # gCO2 avoided / s
sum by (model) (gatewai_requests_total)                   # model mix
# Escalation rate: the cost of the cascade, in requests that reach the model
sum(rate(gatewai_cascade_escalations_total{to_level="llm"}[5m]))
  / sum(rate(gatewai_cascade_escalations_total[5m]))
# Share of routing decisions that were degraded rather than nominal
sum(rate(gatewai_routing_decisions_total{reason=~".*fallback"}[5m]))
  / sum(rate(gatewai_routing_decisions_total[5m]))
```
