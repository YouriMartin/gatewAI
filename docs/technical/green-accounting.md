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

> **Honesty:** the `energyIntensity` coefficients of cloud entries are
> placeholders, so absolute energy/CO2 are directional. Grid-intensity reliability
> (average vs marginal, geo accounting) is covered in
> [`carbon-intensity-reliability.md`](carbon-intensity-reliability.md).

## The scope boundary (v3 lot C.1)

A coefficient of zero and *no coefficient at all* are different statements, and
before C.1 the reports could not tell them apart. `ModelDefinition` now carries an
`EnergySource`:

| `energy-source` | Meaning | Rendered as |
|---|---|---|
| `NOT_ACCOUNTED` | no coefficient; booked at zero; outside the reported totals | **"excluded from scope"** |
| `VENDOR_PUBLISHED` | a figure published by the model vendor | "vendor-published estimate" |
| `MODELLED` | a parametric estimate (to be sourced in lot C.4) | "modelled estimate" |

**Self-hosted (local) egress is `NOT_ACCOUNTED` for v3** — the gateway cannot meter
a model running next to it; that needs host counters (RAPL / NVML) and a per-model
calibration, which is a later lot. So the three shipped Ollama entries declare
`energy-intensity=0` with `energy-source=not-accounted`.

Two invariants keep the label and the number consistent, in the domain record
itself:

- a `NOT_ACCOUNTED` entry with a non-zero coefficient **fails fast at startup** —
  an unaccounted model is booked at zero, not at a leftover number;
- omitting `energy-source` derives it: `0` → `NOT_ACCOUNTED`, otherwise `MODELLED`.

**Consequence on the default all-local setup**: cost, CO2 *and avoided CO2* all read
zero, because the premium baseline the avoided figure is measured against is itself
unaccounted. That is the honest answer — see the rendering rules below.

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
  energy, gCO2, gCO2Avoided) + a `modelMix` (model → count) + an
  `excludedModelMix` (the subset served by `NOT_ACCOUNTED` models).
  `cacheHitRate` is derived.
- `aggregateDaily(logs, from, to)` → one `GreenReport` per UTC day in `[from, to)`,
  **including empty days** so charts are continuous.

The aggregator takes an `EnergySourceLookup` (domain interface) rather than a port,
so it stays dependency-free. Exclusion is counted **per inference**: a cache hit is
never counted as excluded, because no inference ran — that is a genuine zero.

From `excludedModelMix` the report derives everything a renderer needs:
`excludedRequests()`, `accountedRequests()`, `excludedModels()`, an
`emissionsScope()` (`NO_ACTIVITY` | `ALL_ACCOUNTED` | `PARTIALLY_EXCLUDED` |
`ALL_EXCLUDED`), a one-sentence `scopeNote()` that every format prints verbatim,
and `avoidedBasisDiffers()` — true when an excluded actual sits next to a non-zero
avoided figure, which is when `GreenReport.AVOIDED_BASIS_NOTE` becomes mandatory
(ADR 0006: the two are not on the same basis and must not be netted).

`GreenReportService` (application) fetches `requestLogRepository.findBetween(from,
to)` and delegates to the aggregator, resolving the label through the
`ModelRegistry`. `daily(...)` validates the range (`from < to`, max 366 days).
Aggregation is in-memory — fine for an MVP; a very large range would warrant a SQL
`GROUP BY`.

> **Known limit until lot C.5:** the label is read from the **current** registry, so
> a model that has since left it resolves to accounted (never silently dropped from
> the totals). C.5 stores `energy_source` on the row so history stops depending on
> today's configuration.

## Exposure

- `GET /v1/reports/green?from&to&format=json|csv|pdf` — totals; CSV/PDF via
  `GreenReportCsvWriter` / `GreenReportPdfWriter` (OpenPDF), CSRD-oriented.
- `GET /v1/reports/green/series?from&to` — daily series for charts.
- The dashboard's KPIs, sparklines and model-mix bars consume these.
- MCP tool `green_report` exposes the totals to assistants (see [`mcp.md`](mcp.md)).

**No renderer prints a bare zero for an unaccounted model** — that rule is tested
per format:

| Surface | How the exclusion shows |
|---|---|
| JSON | `emissions_scope`, `emissions_scope_note`, `accounted_requests`, `excluded_requests`, `excluded_model_mix`, `excluded_models`, `avoided_basis_note` |
| CSV | `Report,Emissions scope[ note]` rows; every CO2 metric label carries a scope suffix; a `Scope exclusions` section; the model-mix reference column |
| PDF | a basis-of-preparation bullet, the scope note above the E1-6 table, suffixed metric labels, `Inferences in/excluded from scope`, an "Energy accounting" column in the model mix |
| Dashboard | a scope banner, `excluded from scope` in place of the gCO₂ KPI when nothing is accounted, and a chip on each excluded model-mix row |
| MCP | `emissionsScope`, `emissionsScopeNote`, `excludedRequests`, `excludedModels` |

The avoided figure is labelled too: it is derived from the same coefficients, so
with part of the activity unaccounted it cannot be read as complete either.

JSON uses snake_case (`GreenReportResponse`, `@JsonNaming` SnakeCase) for
CSRD-friendly field names. See [`api-reference.md`](api-reference.md) for shapes.

## Real-time grid intensity

`CarbonIntensityProvider` (out port) has a static implementation
(`StaticCarbonIntensityProvider`, per-zone configured values) and a live one
(`ElectricityMapsCarbonIntensityProvider`). Live is **disabled by default**; on any
API error it falls back to the static value. Details and reliability caveats:
[`carbon-intensity-reliability.md`](carbon-intensity-reliability.md).
