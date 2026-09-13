# ADR 0006 — Measure savings as "avoided" vs a premium baseline

**Status:** Accepted

## Context

"This gateway is green" is not credible without a number. The question a buyer asks
is concrete: *what did caching and routing save me?* That requires an explicit
baseline to compare against.

## Decision

Define the headline figures as **avoided cost and avoided CO2**, computed against a
**premium-by-default baseline** — what a request *would* have cost/emitted if sent
to the most capable (premium) model — minus what actually happened:

- **Cache hit**: no inference → real cost/energy/CO2 = 0; the **entire** premium
  baseline is credited as avoided.
- **Miss**: avoided = `max(0, baseline − actual)` for both cost and CO2 (the value
  of routing to a cheaper/greener model).

The premium baseline is the first `CLOUD_PREMIUM` model in the registry.

## Consequences

- The savings story is **explicit and defensible**: the baseline is named, not a
  vague absolute claim.
- The cache's value becomes **visible** (it would otherwise leave no cost trace).
- The figure depends on the baseline choice and on the per-model energy
  coefficients, which are **sourced estimates rather than measurements** since v3
  lot C.4 ([ADR 0013](0013-sourced-and-labelled-not-measured.md)) — so absolute
  carbon is **directional**, and the baseline assumption must be stated when
  reporting. See
  [`../green-accounting.md`](../green-accounting.md) and
  [`../carbon-intensity-reliability.md`](../carbon-intensity-reliability.md).
- **Since v3 lot C.1**, models may be *excluded from scope* (`NOT_ACCOUNTED` —
  every self-hosted entry by default). The arithmetic above is unchanged and stays
  correct, but the two figures are then on different bases: the actual is not
  accounted at all while the baseline is. Wherever an excluded actual is shown next
  to a non-zero avoided figure, the renderers print
  `GreenReport.AVOIDED_BASIS_NOTE` — the avoided number must not be netted against
  an unaccounted one. With the all-local default the baseline is itself unaccounted,
  so the avoided figure is legitimately zero and rendered as excluded rather than
  as a saving.
- **Since v3 lot C.3 each side is priced at its own grid.** The baseline is a
  counterfactual about the *baseline provider's* datacenter, so pricing it at the
  grid of whatever actually answered would reintroduce the wrong-grid error inside
  this very figure ([ADR 0012](0012-region-on-the-provider-instance.md)).
