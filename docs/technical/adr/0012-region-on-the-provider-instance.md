# ADR 0012 — Region attribution belongs to the provider instance

**Status:** Accepted

## Context

Until v3 lot C, `ChatCompletionService` resolved **one** grid intensity for every
request — the gateway's own zone:

```java
double gridIntensity = CarbonZoneContext.CURRENT.isBound()
    ? carbonIntensityProvider.gramsCo2PerKwh(CarbonZoneContext.CURRENT.get())
    : carbonIntensityProvider.gramsCo2PerKwh();
```

So a gateway configured `zone=FR` (56 gCO2/kWh) booked a Claude call — compute that
physically happened in a US datacenter, plausibly 350–400 — at France's intensity.
That is the wrong grid applied to the right energy, and the region was not
*approximate*, it was **absent**.

Three places could have held the region:

- **Per request, from the provider's response.** The honest ideal, and the data does
  not exist: neither the Anthropic nor the OpenAI API says which datacenter served a
  call. That absence is precisely why a provenance label is needed rather than a
  region field alone.
- **On the model-registry entry.** Tempting because the registry is where the other
  carbon coefficients live — and wrong, because it makes an impossible state
  representable: two entries behind `gatewai.providers.anthropic` could declare
  different regions while sharing one connection to one vendor.
- **On the provider instance.** One declaration per connection, which is the unit an
  operator actually picks. Every model behind `gatewai.providers.anthropic` runs
  wherever Anthropic runs.

A second question came bundled with it. Carbon-aware dispatch (Phase 4.4) binds the
greenest candidate zone into a `ScopedValue` and the accounting used it
unconditionally. For a workload the operator places — their own Ollama box, their own
vLLM endpoint — that is meaningful. For a hosted API it is fiction: deferring a job
until Sweden's grid is clean does not move Anthropic's compute.

## Decision

**Region lives on the provider instance**, with two tiers of knowledge and the
datacenter's efficiency alongside it:

```properties
gatewai.providers.vllm.region=eu-west-3          # cloud region id OR grid zone id
gatewai.providers.vllm.region-provenance=known   # known | assumed (default: assumed)
gatewai.providers.vllm.pue=1.15
```

`KNOWN` means the operator chose the region (Bedrock, Azure OpenAI, a box they run).
`ASSUMED` means they declared one without being told, which is the only honest label
for the direct vendor APIs. **Omitting the provenance yields `ASSUMED`**: a region
becomes a fact only when someone says it is.

The intensity is then resolved **per request**, first hit wins:

```
dispatch-chosen zone   (operator-controlled providers only)
  → provider instance region  (mapped to a grid zone)
    → gateway default
```

**For a hosted API the dispatch zone is recorded and not applied.** "Operator
controlled" is derived from the provider type rather than configured again: an
instance qualifies when it is self-hostable (`ollama`, `openai-compatible`) *and* has
not declared an `assumed` region — declaring "assumed" for a hosted OpenAI-compatible
endpoint (OpenRouter, say) is exactly the statement "I do not place this workload".

Cloud regions map to ElectricityMaps zones through a small built-in table
(37 regions, ids verified against the live zone list on 2026-09-13), overridable per
region by configuration. An unrecognised region **warns once and falls back**; a
missing region on a cloud instance warns at startup; a `pue` below 1 fails the
context, because that is not a missing value but an impossible one.

## Consequences

- **The defect is fixed and measured.** A 46-token Claude call books 0.0805 gCO2 at
  PJM's 350 instead of 0.0529 at the gateway's 230, while a local request in the same
  run still books at the gateway default.
- **The avoided figure moved too.** The premium baseline is a counterfactual about
  the *baseline provider's* datacenter, so `GreenAccountant` prices each side at its
  own grid. Otherwise the same error survived one step removed, inside the headline
  "CO2 avoided".
- **`ASSUMED` travels all the way out.** It is stored per row (lot C.5) and named on
  the face of every export, because an operator declaration presented as a fact is
  the failure this ADR exists to prevent.
- **Zero-config boot is untouched.** The default all-Ollama setup declares no region
  and warns about nothing — local egress is out of carbon scope entirely
  ([ADR 0013](0013-sourced-and-labelled-not-measured.md)).
- **The mapping table is an approximation, and says so.** A cloud region is a metro
  area; a grid zone is a balancing authority. Each row names the authority that
  predominantly serves that region's known datacenter sites — the best attribution
  available while providers publish none.
- **The shipped assumed zone is `US-MIDA-PJM`, not `US`.** The country-level zone is
  an *aggregate* in ElectricityMaps with no live data tier (checked 2026-09-13), so
  declaring it would silently disable live intensity for every cloud call.
- **A mistyped zone id is indistinguishable from an unknown one.** A value shaped
  like a zone id is passed through verbatim, which is what makes `region=FR` work
  without a table entry. The stored zone (lot C.5) is what makes a wrong one visible
  afterwards.
- **What this does not buy: physical relocation.** Temporal shifting stays real — the
  job genuinely runs later. Geography stays *accounting*, which is exactly why the
  dispatch zone is no longer applied to someone else's datacenter.
- **Reversible.** Everything hangs off two out ports (`ProviderRegions`,
  `CloudRegionZones`) and a pure-domain `CarbonZoneResolver`; a deployment that wants
  the old behaviour declares no regions and gets the gateway default everywhere.
