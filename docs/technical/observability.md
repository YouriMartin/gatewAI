# Observability (Phase 6.1)

Micrometer metrics exported in Prometheus format, on two levels.

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
| `gatewai_classifier_cascade_level_total` | counter | `level` (`deterministic`/`embedding`/`llm`) | classifications by the deepest cascade level reached (v2 batch 4) |

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
trace has holes, not that requests are failing. The full decision metrics
(`gatewai.routing.decisions`, `gatewai.cache.decisions`, margins, escalations)
arrive with batch 6.

Common tag `application=gatewai` on every series.

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
- Grafana: http://localhost:3000 (anonymous admin), Prometheus data source =
  `http://prometheus:9090`

PromQL examples:

```promql
sum(rate(gatewai_co2_avoided_grams_total[5m]))            # gCO2 avoided / s
sum by (model) (gatewai_requests_total)                   # model mix
gatewai_cache_hits_total / (gatewai_cache_hits_total + gatewai_cache_misses_total)
```
