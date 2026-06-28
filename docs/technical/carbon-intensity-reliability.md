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
2. **Coarse absolute carbon**: grid intensity × `energyIntensity` coefficients
   (kWh/token) that are **placeholders**. Good grid data does not save an
   approximate upstream energy estimate.
3. **Temporal** shifting (the `@Scheduled` worker that defers execution) =
   **real**.

## 5. Recommended posture

- **Portfolio presentation**: "carbon-aware routing, directionally correct, same
  method as Google/Microsoft" — owning the limits.
- **For audited carbon claims (CSRD)**, you would need to:
  - move to **marginal** intensity (WattTime);
  - use **measured energy factors** (not the kWh/token placeholders);
  - have a real **multi-region execution** (regional endpoints);
  - document an auditable methodology (datacenter PUE, Scope 2/3 boundary).

## Summary

| Question | Honest answer |
|---|---|
| Do we know which zone is greenest? | Yes — the **ranking** is reliable. |
| Are the **absolute numbers** reliable? | Moderately (marginal ≠ average, uncertain factors, revisions). |
| Is our implementation "real"? | Temporal is real; geo = accounting; energy = placeholders. |
