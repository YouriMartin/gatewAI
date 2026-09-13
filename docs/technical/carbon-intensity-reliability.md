# Zone carbon intensity: method & reliability

Reference note on **how we know a zone's carbon intensity**, **how it is computed
upstream**, and **what the reliability limits are**. Keep it handy for presenting
the project and for Phase 4.5 (CSRD-style reporting).

Related to: `CarbonIntensityProvider`, `CarbonAwareZoneSelector`,
`ElectricityMapsCarbonIntensityProvider`, `StaticCarbonIntensityProvider`, the
carbon model (Phase 4.1) and dispatch (Phase 4.4).

---

## 1. How we obtain the data (in the project)

Two sources behind the same `CarbonIntensityProvider` abstraction:

- **Static** (`gatewai.carbon.zone-intensities`): hard-coded values (FR 56, SE 30,
  DE 380, PL 650 gCO2/kWh). **Demo only** — realistic orders of magnitude but
  frozen.
- **ElectricityMaps** (real-time): `GET /carbon-intensity/latest?zone=XX` →
  `carbonIntensity` field in gCO2eq/kWh. The `CarbonAwareZoneSelector` takes the
  **minimum** over the candidate zones.

On the gateway side, "knowing" = *asking a data provider*. The real question is
how **it** knows.

## 2. How ElectricityMaps / WattTime compute it (physical chain)

1. **Near real-time generation mix** published by grid operators (RTE, ENTSO-E,
   EIA): MW per source (coal, gas, nuclear, hydro, wind, solar…).
2. **Life-cycle emission factor** per source (gCO2eq/kWh, IPCC ranges): coal
   ~820, gas ~490, solar ~45, hydro ~24, nuclear ~12, wind ~11.
3. **Intensity = weighted sum** of the mix by those factors.
4. **Import/export flows**: *flow-tracing* for the electricity *consumed* (not
   just produced) in the zone — **modeled, not measured**.

> Same approach as **Google** (Carbon-Intelligent Computing) and **Microsoft** to
> shift their workloads → a legitimate technique, in production at hyperscalers.

## 3. Reliability

### What IS reliable (directional signal)
- The gap **between zones** is massive and structural (30 vs 650 gCO2/kWh).
  "Sweden is greener than Poland" is true at any instant.
- For a binary "zone A or B?" decision, the real-time average is enough.

### What is LESS so (pitfalls)
- **Average vs marginal — pitfall #1.** ElectricityMaps gives the *average*
  intensity of the mix. But an *additional* load is served by the **marginal**
  plant (often peaking gas/coal). A zone with a low average can have a high
  marginal. **WattTime** targets the marginal, which is the right signal for
  *load-shifting*. **Our code uses the average.**
- **Estimate revised after the fact**: latency, missing data (estimated zones),
  corrections.
- **Uncertain life-cycle factors** (solar varies threefold depending on the
  study).
- **Zone-level granularity, not datacenter**: solar PPAs / origin contracts are
  not reflected.

## 4. Limits specific to our implementation

1. **No physical relocation**: we choose the *accounting* zone, not a real
   multi-region execution. **Accounting** benefit, not physical.
   Since v3 lot C.2 the accounting zone is at least **attributed**: each egress
   provider instance declares `region`, `region-provenance` (`known` | `assumed`)
   and `pue`, and a small built-in table maps cloud regions to ElectricityMaps
   zones (ids verified 2026-09-13; config overrides per region; an unknown region
   warns once and falls back). A hosted API's region is `ASSUMED` by construction —
   neither Anthropic nor OpenAI publishes which datacenter served a call — and
   every export has to say so.
   Lot C.3 *applies* it per request: the intensity is resolved per model
   (dispatch zone for operator-controlled providers → provider region → gateway
   default), and the premium baseline of the avoided figure is priced at **its**
   provider's grid rather than the served model's. A dispatch-chosen zone is
   **recorded and not applied** for a hosted API — the accounting-versus-physical
   gap above, now a code path instead of a caveat.
2. **Coarse absolute carbon**, and now explicitly scoped (v3 lot C.1). Each
   registry entry carries an `energy-source` label, and the reports render it:
   - **`NOT_ACCOUNTED`** — self-hosted (local) egress, the shipped default. Its
     energy is not metered and not estimated, so it contributes nothing and every
     export says *"excluded from scope"* rather than printing `0 gCO2`. Metering
     it (RAPL / NVML host counters + per-model calibration) is a later lot.
   - **`MODELLED`** — cloud entries. Since v3 lot C.4 these are no longer
     placeholders but **sourced** estimates: a prefill/decode split derived from
     EcoLogits' published parametric method and NVIDIA's H100 figures, with every
     input, its URL and its read-date in
     [`green-accounting.md`](green-accounting.md#the-coefficients-and-where-they-come-from).
     Sourced is not measured: for a closed model the active-parameter count is
     itself an estimate (200–600 B for the Opus class), so the honest output is a
     range, of which only the midpoint is stored.
   - **`VENDOR_PUBLISHED`** — a figure the vendor measured (Google's median
     Gemini-prompt energy). Highest credibility, spotty coverage, and full-stack:
     its PUE is already inside, so the gateway does not apply one.
3. **Temporal** shifting (the `@Scheduled` worker that defers execution) =
   **real**.

## 5. Recommended posture

- **Portfolio presentation**: "carbon-aware routing, directionally correct, same
  method as Google/Microsoft" — owning the limits.
- **For audited carbon claims (CSRD)**, you would need to:
  - move to **marginal** intensity (WattTime);
  - use **measured energy factors**. Lot C.4 made the cloud figures *sourced,
    dated and phase-split*, which is a step short of measured: the parametric
    inputs are published, the parameter counts behind them are not. Measuring
    local inference is later still;
  - have a real **multi-region execution** (regional endpoints);
  - document an auditable methodology (datacenter PUE, Scope 2/3 boundary).

## Summary

| Question | Honest answer |
|---|---|
| Do we know which zone is greenest? | Yes — the **ranking** is reliable. |
| Are the **absolute numbers** reliable? | Moderately (marginal ≠ average, uncertain factors, revisions). |
| Is our implementation "real"? | Temporal is real; geo = accounting; cloud energy = **sourced and labelled** estimates (phase-split, with citations and read-dates), never measured; local energy = **not accounted at all**, and said so. |
