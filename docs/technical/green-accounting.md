# Green accounting & reporting

How gatewAI turns token usage into cost, energy and carbon, computes the avoided
figures, persists them, and aggregates them into reports. Sources:
`domain/model/CarbonCalculator`, `GreenAccountant`, `GreenMetrics`,
`CarbonFootprint`, `ReportAggregator`; `application/service/ChatCompletionService`
and `GreenReportService`.

## The carbon model

`CarbonCalculator.estimate(site, usage)` is pure domain logic:

```
kWh      = prefill × promptTokens/1000          // compute-bound, batched
         + decode  × completionTokens/1000      // bandwidth-bound, sequential
         + fixed                                // per prompt, for vendor figures
kWh      = kWh × PUE                            // unless the figure already includes it
gramsCo2 = kWh × gridIntensityGramsPerKwh
```

A `ModelSite` carries the model **plus the conditions it ran under** — the grid
intensity (lot C.3) and the datacenter's PUE (lot C.2) — so the same class prices a
request at the grid that actually served it without knowing where that came from.
`TokenUsage` carries prompt, completion and total: energy uses the two phases, cost
uses the total the provider billed.

**Why the split (v3 lot C.4).** Prefill processes the whole prompt in parallel and
saturates the GPU's arithmetic units; decode emits one token at a time, re-reading
every weight, and is limited by memory bandwidth. Per token the two differ by more
than an order of magnitude. With one scalar per 1k tokens, a 10k-token prompt with a
50-token answer and its mirror image produced *identical* figures. `RequestLog` had
persisted the two counts separately since Phase 4.3; only the model threw them away.

> **Honesty:** nothing here is measured. Cloud coefficients are **sourced and
> labelled** estimates — see [the coefficient table](#the-coefficients-and-where-they-come-from)
> — and self-hosted egress is excluded from scope entirely (lot C.1).
> Grid-intensity reliability (average vs marginal, geo accounting) is covered in
> [`carbon-intensity-reliability.md`](carbon-intensity-reliability.md).

## The coefficients, and where they come from

Every figure below was read from its source on **2026-09-13**, and the arithmetic
that turns it into a coefficient is written out. A number recalled from memory is not
a citation.

### PUE

| Value | Applies when | Source |
|---|---|---|
| the provider's `pue` | declared on the instance (lot C.2) | operator |
| **1.2** (`CarbonCalculator.DEFAULT_PUE`) | no `pue` declared | EcoLogits' methodology default; its per-provider table spans 1.09–1.20, so the top of that range never flatters an unknown datacenter — [ecologits.ai](https://ecologits.ai/latest/methodology/llm_inference/) |
| **not applied** | `energy.includes-datacenter-overhead=true` | a full-stack vendor figure already carries cooling and conversion; multiplying again would count it twice |

### `MODELLED` — the Claude-Opus-class example

The parametric route is EcoLogits' (the EU-published, ISO-14044-framed LLM inference
method). Inputs, all quoted:

| Input | Value | Source |
|---|---|---|
| GPU energy per output token | `f(P) = α·e^(βB)·P + γ`, α = 1.17×10⁻⁶, β = −1.12×10⁻², γ = 4.05×10⁻⁵ (Wh/token, P in **billions** of active parameters, batch B) | [EcoLogits LLM inference methodology](https://ecologits.ai/latest/methodology/llm_inference/) |
| Batch size | 64 (their fixed serving assumption) | idem |
| Reference GPU | NVIDIA H100 80 GB, 16-bit weights | idem |
| GPUs needed | `⌈(P_total × bits/8 × 1.2) / 80 GB⌉` → **60** for a ~2T-parameter model | [EcoLogits 0.4 methodology](https://ecologits.ai/0.4/methodology/llm_inference/) |
| Active parameters | **200–600 B**, ~2 T total, for the Claude Opus class | [EcoLogits proprietary-model estimates](https://ecologits.ai/latest/methodology/proprietary_models/) — reasoned from benchmark parity with GPT-4, treated as a sparse MoE |
| Forward-pass compute | ≈ 2 FLOPs per parameter per token | standard scaling-law accounting (Kaplan et al. 2020 give ≈ 6N per token for training, i.e. 2N forward) |
| H100 dense BF16 throughput | 989 TFLOPS (half the 1,979 TFLOPS NVIDIA quotes *with sparsity*), 700 W TDP | [NVIDIA H100 product page](https://www.nvidia.com/en-us/data-center/h100/) |
| Achieved prefill utilisation | **40 %** — an assumption, stated as one | — |

Arithmetic at the midpoint assumption (400 B active, 2 T total):

```
decode  = (1.17e-6 × e^(-0.0112×64) × 400 + 4.05e-5) Wh/token × 60 GPUs
        = 2.690e-4 × 60 = 1.614e-2 Wh/token → 0.0161 kWh per 1k completion tokens
prefill = 2 × 400e9 FLOPs / (0.40 × 989e12 FLOPS) × 700 W
        = 1.415 J/token → 0.000393 kWh per 1k prompt tokens
fixed   = 0
```

The 200–600 B range gives decode **0.0093–0.0230** and prefill
**0.000197–0.000590**. Only the midpoint is stored: propagating an interval through
`CarbonFootprint` → `GreenMetrics` → the schema touches every report surface and is
post-v3. The range is documented instead, which is the honest half-measure.

Three things this number is not:

- **Not measured.** It is a published parametric fit, extrapolated to a model whose
  parameter count nobody outside Anthropic knows.
- **Not conservative in the flattering direction.** Multiplying per-GPU token energy
  by all 60 GPUs assumes every one of them is busy for the whole generation, and the
  implied throughput (~11 tokens/s per stream at batch 64) is lower than frontier
  APIs actually stream. If real throughput is 3× that, the true figure is ~3× lower.
- **Not comparable to a vendor figure.** It lands ~10× above Google's measured
  median-prompt number below. Different model, different silicon, different stack —
  which is exactly why the `EnergySource` label travels with the number.
- **`fixed = 0`, deliberately.** EcoLogits' only non-token term is proportional to
  generation latency (≈2 % of the GPU term at a 10 s generation), so there is no
  honest constant to put there; inventing a reference latency would add false
  precision.

### `VENDOR_PUBLISHED` — the Gemini example

| Coefficient | Value | Source |
|---|---|---|
| `fixed-kwh-per-request` | **0.00024** kWh (0.24 Wh) | ["Measuring the environmental impact of delivering AI at Google Scale", arXiv:2508.15734, 21 Aug 2025](https://arxiv.org/abs/2508.15734) — median Gemini Apps **text prompt** |
| `includes-datacenter-overhead` | `true` | the paper's scope: "active AI accelerator power, host system energy, idle machine capacity, and data center energy overhead" |

A vendor figure published *per prompt* is exactly what the `fixed` term is for — and
its scope is why PUE must not be applied on top. Stated caveat: 0.24 Wh is a **median
over a request mix**, not a per-model API figure, so using it for one model id is an
approximation the label does not excuse.

### A published figure deliberately **not** used

Mistral's LCA of Mistral Large 2 (with ADEME and Carbone 4, ISO 14040/44, July 2025)
reports **1.14 gCO2e per 400-token response**. It is not in the table because it is
*emissions*, not energy: converting it back to kWh would need their grid intensity,
and it includes embodied impacts, which are outside lot C's location-based Scope 2
boundary. Citing it as an energy coefficient would be arithmetic laundering.

### Self-hosted

All three local entries stay at zero with `energy.source=not-accounted` — see [the
scope boundary](#the-scope-boundary-v3-lot-c1). No coefficient is owed for a model
the gateway cannot meter.

## The scope boundary (v3 lot C.1)

A coefficient of zero and *no coefficient at all* are different statements, and
before C.1 the reports could not tell them apart. `ModelDefinition` now carries an
`EnergySource`:

| `energy.source` | Meaning | Rendered as |
|---|---|---|
| `NOT_ACCOUNTED` | no coefficient; booked at zero; outside the reported totals | **"excluded from scope"** |
| `VENDOR_PUBLISHED` | a figure published by the model vendor | "vendor-published estimate" |
| `MODELLED` | a sourced parametric estimate ([table](#the-coefficients-and-where-they-come-from)) | "modelled estimate" |

**Self-hosted (local) egress is `NOT_ACCOUNTED` for v3** — the gateway cannot meter
a model running next to it; that needs host counters (RAPL / NVML) and a per-model
calibration, which is a later lot. So the three shipped Ollama entries declare
`energy.source=not-accounted` and no coefficients.

Two invariants keep the label and the numbers consistent, in the domain value object
(`EnergyProfile`) itself:

- a `NOT_ACCOUNTED` entry with **any** non-zero coefficient **fails fast at
  startup** — an unaccounted model is booked at zero, not at a leftover number;
- omitting `energy.source` derives it: all-zero → `NOT_ACCOUNTED`, otherwise
  `MODELLED`. A negative coefficient is refused outright.

**Consequence on the default all-local setup**: cost, CO2 *and avoided CO2* all read
zero, because the premium baseline the avoided figure is measured against is itself
unaccounted. That is the honest answer — see the rendering rules below.

## Where a provider runs (v3 lot C.2)

Region belongs to the **provider instance**, not to the model: every model behind
`gatewai.providers.anthropic` runs wherever Anthropic runs. Three properties per
instance:

```properties
gatewai.providers.vllm.type=openai-compatible
gatewai.providers.vllm.region=eu-west-3          # cloud region id OR grid zone id
gatewai.providers.vllm.region-provenance=known   # known | assumed (default: assumed)
gatewai.providers.vllm.pue=1.15                  # >= 1.0; carried now, applied in C.4
```

Two tiers of knowledge, and the config keeps them apart because a report must:

| | Region is | Examples |
|---|---|---|
| `KNOWN` | a fact — the operator picked it | Bedrock, Azure OpenAI, a vLLM box, any `openai-compatible` endpoint you host |
| `ASSUMED` | an operator declaration | the direct Anthropic and OpenAI APIs, which do not say which datacenter served a call |

Omitting `region-provenance` yields **`ASSUMED`**: a region only becomes a fact when
someone says it is. `ProviderRegion` (domain) carries the three values, and the
`ProviderRegions` out port exposes them — `PropertiesProviderRegions` reads the
properties and logs the declared set at startup.

**Failure modes, deliberately asymmetric:**

- a referenced non-Ollama instance with **no region** → `WARN` at startup, boot
  continues. It is then booked at the gateway's own zone, which is the defect lot C
  exists to fix; but refusing to boot over a carbon-accounting detail would break the
  zero-config promise. The default all-Ollama setup declares no region and warns
  about nothing — local egress is out of carbon scope entirely (C.1);
- `pue < 1.0` → **fails the context**, naming the property. That is not a missing
  value, it is an impossible one: a datacenter cannot deliver more energy to its
  servers than it draws.

### Cloud region → grid zone

`CloudRegionZones` (out port) translates `us-east-1` into `US-MIDA-PJM`. Resolution
order, first hit wins:

1. `gatewai.carbon.region-zones.<region>` — the operator's own mapping, and how a
   region no release knows about gets attributed;
2. the built-in table (below);
3. the input itself when it already **looks like** a zone id (`FR`,
   `US-MIDA-PJM`) — the `region` property accepts either form;
4. nothing: an unrecognised region is logged **once** and resolves to empty, so the
   caller falls back to the gateway's default zone. Never an exception.

The built-in table, with the balancing authority that predominantly serves each
region's known datacenter sites. **Every zone id was verified against the
ElectricityMaps zone list** (`GET /v3/zones`, 350 zones) on **2026-09-13**:

| Cloud region | Grid zone | Cloud region | Grid zone |
|---|---|---|---|
| `us-east-1`, `us-east-2` (AWS) | `US-MIDA-PJM` | `us-central1` (GCP) | `US-MIDW-MISO` |
| `us-west-1` | `US-CAL-CISO` | `us-east4` | `US-MIDA-PJM` |
| `us-west-2` | `US-NW-PACW` | `us-west1` | `US-NW-PACW` |
| `ca-central-1` | `CA-QC` | `europe-west1` | `BE` |
| `eu-west-1` | `IE` | `europe-west2` | `GB` |
| `eu-west-2` | `GB` | `europe-west3` | `DE` |
| `eu-west-3` | `FR` | `europe-west4` | `NL` |
| `eu-central-1` | `DE` | `europe-west9` | `FR` |
| `eu-north-1` | `SE-SE3` | `europe-north1` | `FI` |
| `ap-northeast-1` | `JP-TK` | `asia-northeast1` | `JP-TK` |
| `ap-southeast-1` | `SG` | `asia-southeast1` | `SG` |
| `ap-southeast-2` | `AU-NSW` | `eastus`, `eastus2` (Azure) | `US-MIDA-PJM` |
| `ap-south-1` | `IN-WE` | `westus2` | `US-NW-BPAT` |
| `sa-east-1` | `BR-CS` | `northeurope` / `westeurope` | `IE` / `NL` |
| | | `uksouth` | `GB` |
| | | `francecentral` | `FR` |
| | | `germanywestcentral` | `DE` |
| | | `swedencentral` | `SE-SE3` |
| | | `japaneast` | `JP-TK` |
| | | `australiaeast` | `AU-NSW` |

**Two limits worth naming.** A cloud region is a metro area and a grid zone is a
balancing authority, so each row is an approximation an operator can override — not
a published fact. And the pass-through in step 3 means a *mistyped* zone id is
indistinguishable from one the gateway has not heard of: it is taken at face value,
and the grid-intensity provider falls back to its default for an id it cannot price.
Storing the resolved zone on the row (lot C.5) is what makes a wrong one visible
afterwards.

The shipped `anthropic` / `openai` instances declare `region=US-MIDA-PJM` with
`provenance=assumed`. Neither vendor publishes which datacenter served a request;
PJM is where the largest concentration of US-East cloud capacity sits. The
country-level zone `US` would read as more honest but is an *aggregate* zone with no
live data tier in ElectricityMaps (checked 2026-09-13), so it cannot be priced live
at all.

> Lot C.4 is what applies the PUE. C.2 declared the region and the mapping; C.3
> (below) is what resolves and applies it per request.

## Which grid a request is booked at (v3 lot C.3)

Before C.3, `ChatCompletionService` resolved **one** intensity for every request —
the gateway's own zone — so a gateway configured for France booked a Claude call,
US compute, at France's 56 gCO2/kWh. That was the wrong grid applied to the right
energy, and it is the cheapest error in the carbon path to fix.

`CarbonZoneResolver` (pure domain) owns the precedence; the application service
gathers the facts. First hit wins:

| Step | Applies when | `CarbonZoneSource` |
|---|---|---|
| dispatch-chosen zone | the provider is **operator-controlled** | `DISPATCH` |
| provider instance region, mapped to a zone | a region is declared and mappable | `PROVIDER_REGION` |
| the gateway's own default | nothing more specific is known | `GATEWAY_DEFAULT` |

**For a hosted API the dispatch zone is recorded, not applied.** Deferring a job
does not move Anthropic's compute, so a deferred call still books at the provider's
grid while `ResolvedCarbonZone.dispatchZone()` keeps what dispatch picked
(`dispatchRecordedOnly()`). This turns the accounting-versus-physical caveat in
[`carbon-intensity-reliability.md`](carbon-intensity-reliability.md) §4.1 from a
footnote into a code path. Persisting both sides is lot C.5.

**Operator-controlled** is derived in the adapter, from the provider type: an
instance is controlled when it is self-hostable (`ollama`, `openai-compatible`)
*and* has not declared an `assumed` region — declaring "assumed" for a hosted
OpenAI-compatible endpoint (OpenRouter, say) is exactly the statement "I do not
place this workload". An **unknown** provider is never controlled: a dispatch zone
is only applied to a workload the operator is known to place.

`GATEWAY_DEFAULT` carries a **null zone** on purpose. The gateway's default lives
behind `CarbonIntensityProvider.gramsCo2PerKwh()`; naming a zone there would change
the value that method returns (the static provider would start reading
`zone-intensities`), so the resolver says "no attribution" instead of inventing one.

### The premium baseline is priced at its own grid

`GreenAccountant.account(...)` now takes **two** intensities: one for the served
model, one for the premium baseline. The baseline is a counterfactual about the
*baseline provider's* datacenter — pricing "what Claude would have emitted" at the
grid of the local box that actually answered is the same wrong-grid error, one step
removed. When both models sit behind the same provider the two values are equal, and
the single-intensity overload still exists for callers with no region information.

Measured on a real run (`mock` egress, premium tier repointed at `anthropic`
`region=US-MIDA-PJM` at 350 gCO2/kWh, gateway default 230):

| Request | Row | Reading |
|---|---|---|
| premium, sync | 46 tok → 0.00023 kWh → **0.0805 gCO2** | 0.00023 × 350; it was 0.0529 before C.3 |
| local, sync | 0 kWh → 0 gCO2, **avoided 0.0245** | excluded from scope (C.1); avoided = 14 tok at *Anthropic's* 350 |
| deferred, `chosen_zone=SE`, local tier | 0.000104 kWh → **0.00312 gCO2** | × 30 — dispatch applies on your own box |
| deferred, `chosen_zone=SE`, premium tier | 0.00029 kWh → **0.1015 gCO2** | × 350, not × 30 — recorded, not applied |

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
(`ChatCompletionService.accountGreen`). Each side's grid intensity comes from
`CarbonIntensityProvider` at the zone the resolver picked for *that* model — see
[the resolution chain](#which-grid-a-request-is-booked-at-v3-lot-c3).

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

> **Note on the scope label:** the exclusion count is derived from the **current**
> registry, so a model that has since left it resolves to accounted (never silently
> dropped from the totals). Each row also stores its own `energy_source` since lot
> C.5, which is what the regional breakdown and the export labels read.

## Rows that explain themselves (v3 lot C.5)

Until C.5 a report could only be read against the registry as it stood when the report
ran: edit a coefficient or a provider region and every historical row silently changed
meaning — the stored gCO2 stayed put while the explanation of it moved. `RequestLog`
now carries a `GreenProvenance` (Flyway `V9__green_provenance.sql`):

| Column | What it answers |
|---|---|
| `provider` | by-provider attribution without resolving a model id against today's registry |
| `grid_zone` | which grid the emissions were booked at (`NULL` = the gateway's own default) |
| `grid_intensity_g_per_kwh` | the intensity actually applied, so `grams_co2 = energy_kwh × intensity` is checkable on the row |
| `grid_zone_source` | which step of the C.3 chain supplied the zone |
| `dispatch_zone` | what carbon-aware dispatch chose. Set, with `grid_zone_source ≠ DISPATCH`, means **recorded and not applied** — the C.3 distinction, now in the data |
| `region_provenance` | whether that region was a fact or an operator declaration |
| `energy_source` | what kind of estimate the energy was |
| `pue` | the datacenter overhead already inside `energy_kwh` |

Every column is nullable, and rows written before V9 keep their NULLs: they come back
as `GreenProvenance.UNKNOWN` and aggregate under `unattributed` / `unknown` rather
than as a fabricated attribution.

**What is deliberately not stored: the coefficient set.** `energy_kwh` is the output of
the model in force at the time, so history is immutable by construction and editing a
coefficient only moves new rows — asserted in `ChatCompletionServiceCarbonZoneTest`.
But re-deriving kWh *from the token counts* would need a per-row snapshot of
prefill/decode/fixed. That is the next step, not this one, and until it exists a row
proves its emissions arithmetic but not its energy arithmetic.

### What the reports gained

`EmissionsBreakdown` (built by `ReportAggregator` from the stored rows, never from the
live registry) carries gCO2 **by grid zone** and **by provider**, plus the zones whose
region was only *assumed*. Every surface states the accounting basis
(`GreenReport.SCOPE_BASIS` — location-based Scope 2 only, market-based not computed)
and names assumed regions on its face:

| Surface | Where it shows |
|---|---|
| JSON / MCP | `scope_basis`, `grams_co2_by_region`, `grams_co2_by_provider`, `assumed_regions`, `assumed_region_note` |
| CSV | `Report,GHG accounting basis` and `Report,Assumed regions` header rows; `GHG emissions by region` / `by provider` sections, each zone row noting `region assumed, not known` or `no region attributed` |
| PDF | the basis of preparation states the basis and the assumed regions; section 5 attributes emissions by zone and provider with a per-zone confidence column |
| Dashboard | an "Emissions attribution" panel: gCO₂ by zone and by provider, with a chip on assumed or unattributed zones |

A zone that emitted **nothing** still appears in the breakdown — local egress served
traffic even though its energy is out of scope, and dropping it would make the report
look like that traffic never happened.

Measured on a real run (mock egress, three providers, `V9` applied by Flyway):

| Row | Attribution | Check |
|---|---|---|
| `claude-opus-4-8` | `anthropic` / `US-MIDA-PJM` / 350 / **ASSUMED** / `MODELLED` / PUE 1.12 | 0.00023925776 kWh × 350 = 0.083740216 gCO2 ✓ |
| `mistral-large` | `vllm` / **FR** (from `eu-west-3`) / 56 / **KNOWN** / `MODELLED` / PUE 1.15 | 6.9e-5 × 56 = 0.003864 ✓ |
| `qwen2.5:0.5b` | `ollama` / no zone / 230 (gateway default) / `NOT_ACCOUNTED` | 0, excluded from scope |

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
