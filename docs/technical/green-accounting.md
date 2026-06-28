# Green accounting & reporting

How gatewAI turns token usage into cost, energy and carbon, computes the avoided
figures, persists them, and aggregates them into reports. Sources:
`domain/model/CarbonCalculator`, `GreenAccountant`, `GreenMetrics`,
`CarbonFootprint`, `ReportAggregator`; `application/service/ChatCompletionService`
and `GreenReportService`.

## The carbon model

`CarbonCalculator.estimate(model, tokens, gridIntensity)` is pure domain logic:

```
energyKwh = (tokens / 1000) × model.energyIntensity   // kWh per 1k tokens
gramsCo2  = energyKwh × gridIntensityGramsPerKwh
```

Returns a `CarbonFootprint(energyKwh, gramsCo2)`. The grid intensity is **supplied
by the caller**, so the same class works with a static constant today or a live
value from `CarbonIntensityProvider` (Phase 4.2) with no change.

> **Honesty:** `energyIntensity` values are placeholders; absolute energy/CO2 are
> directional. Grid-intensity reliability (average vs marginal, geo accounting) is
> covered in
> [`carbon-intensity-reliability.md`](carbon-intensity-reliability.md).

## Per-request accounting

`GreenAccountant.account(used, premiumBaseline, totalTokens, gridIntensity,
cacheHit)` produces a `GreenMetrics(costEur, energyKwh, gramsCo2, costAvoidedEur,
gramsCo2Avoided)`:

- `totalTokens <= 0` → `GreenMetrics.ZERO`.
- **Cache hit**: no inference happened → real cost/energy/CO2 = 0, and the **whole
  premium-default call is credited as avoided** (`avoidedCost`, `avoidedCo2`
  computed for the baseline). This makes the cache's saving visible — it would
  otherwise be invisible.
- **Miss** with a known `used` model: compute real `costEur` and footprint, and the
  avoided figures = `max(0, baseline − actual)` for both cost and CO2 (the value of
  routing to a cheaper/greener model than the premium default).

Cost = `(totalTokens / 1000) × costPer1kTokens`.

The **premium baseline** is the first `CLOUD_PREMIUM` model in the registry
(`ChatCompletionService.accountGreen`). The grid intensity comes from
`CarbonIntensityProvider`, using the zone from `CarbonZoneContext` when bound
(deferred jobs) or the default zone otherwise.

## Wiring in the request path

`ChatCompletionService.complete(...)` (application layer, **not** an advisor):

1. time the `LlmClient.call(...)`;
2. `accountGreen(response)` → `GreenMetrics`;
3. build a `RequestLog` (id, timestamp, model, prompt hash, tokens, latency,
   clientId, green metrics, cacheHit) and `requestLogRepository.save(...)`;
4. `metricsRecorder.record(log)` (Micrometer — see
   [`observability.md`](observability.md)).

The `clientId` comes from the `RequestContext` Scoped Value; the prompt is hashed
(SHA-256) rather than stored.

## Aggregation & reporting

`ReportAggregator` (pure domain) sums `RequestLog` rows into a `GreenReport`:

- `aggregate(logs, from, to)` → totals (requests, cacheHits, cost, costAvoided,
  energy, gCO2, gCO2Avoided) + a `modelMix` (model → count). `cacheHitRate` is
  derived.
- `aggregateDaily(logs, from, to)` → one `GreenReport` per UTC day in `[from, to)`,
  **including empty days** so charts are continuous.

`GreenReportService` (application) fetches `requestLogRepository.findBetween(from,
to)` and delegates to the aggregator. `daily(...)` validates the range
(`from < to`, max 366 days). Aggregation is in-memory — fine for an MVP; a very
large range would warrant a SQL `GROUP BY`.

## Exposure

- `GET /v1/reports/green?from&to&format=json|csv|pdf` — totals; CSV/PDF via
  `GreenReportCsvWriter` / `GreenReportPdfWriter` (OpenPDF), CSRD-oriented.
- `GET /v1/reports/green/series?from&to` — daily series for charts.
- The dashboard's KPIs, sparklines and model-mix bars consume these.
- MCP tool `green_report` exposes the totals to assistants (see [`mcp.md`](mcp.md)).

JSON uses snake_case (`GreenReportResponse`, `@JsonNaming` SnakeCase) for
CSRD-friendly field names. See [`api-reference.md`](api-reference.md) for shapes.

## Real-time grid intensity

`CarbonIntensityProvider` (out port) has a static implementation
(`StaticCarbonIntensityProvider`, per-zone configured values) and a live one
(`ElectricityMapsCarbonIntensityProvider`). Live is **disabled by default**; on any
API error it falls back to the static value. Details and reliability caveats:
[`carbon-intensity-reliability.md`](carbon-intensity-reliability.md).
