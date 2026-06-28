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
- The figure depends on the baseline choice and on the (placeholder) per-model
  energy coefficients — so absolute carbon is **directional**, and the baseline
  assumption must be stated when reporting. See
  [`../green-accounting.md`](../green-accounting.md) and
  [`../carbon-intensity-reliability.md`](../carbon-intensity-reliability.md).
