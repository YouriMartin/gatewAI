# ADR 0013 — Sourced and labelled, not measured

**Status:** Accepted

## Context

The project's headline is a carbon figure, and the honest position on that figure is
uncomfortable: **the gateway cannot measure energy.** It sees tokens, latency and a
model id. Watts are on the other side of an API boundary for cloud egress, and behind
host-level counters for self-hosted egress.

What v2 shipped instead was a scalar per model — `0.0005`, `0.001`, `0.002` kWh per 1k
tokens — a made-up geometric sequence whose only property was preserving the order
premium > entry > local. It was documented as *"rough placeholders"* in a properties
comment, while the dashboard printed gCO2 to one decimal and the CSV export carried an
ESRS E1-6 reference. The estimate was defensible; its *presentation* was not, because
nothing downstream of the coefficient knew it was a guess.

Three ways out were available:

- **Measure it.** Impossible for cloud inference from this side of the wire. Possible
  in principle for self-hosted inference (RAPL for CPU packages, NVML for NVIDIA GPUs,
  amdgpu hwmon), but that needs host privileges the gateway does not have, a per-model
  calibration run, and a way to attribute a shared GPU across concurrent requests.
- **Drop the feature.** Report cost and cache hit rate, say nothing about carbon. It
  removes the false precision and the reason the project exists with it.
- **Make every number say what kind of number it is**, and cite it.

## Decision

Every energy figure carries a **provenance label that travels to every render
surface**, and the totals state their accounting basis:

| Label | Meaning |
|---|---|
| `NOT_ACCOUNTED` | no coefficient at all. Booked at zero, rendered as **"excluded from scope"**, never as `0 gCO2` |
| `VENDOR_PUBLISHED` | a figure the vendor measured and published |
| `MODELLED` | a parametric estimate, with its method, URL and read-date recorded |

Concretely:

- **Self-hosted egress is out of scope** for v3 — the shipped default, so the
  out-of-the-box report says "excluded from scope" rather than zero.
- Cloud coefficients are a **prefill/decode split** (`a × promptTokens +
  b × completionTokens + c`), each derived from published inputs with the arithmetic
  written out in
  [`../green-accounting.md`](../green-accounting.md#the-coefficients-and-where-they-come-from).
  Where an input is itself a range — a closed model's active-parameter count — the
  range is documented and the midpoint stored.
- Every stored row carries its own labels, grid and intensity, so a report of an old
  period still describes that period (lot C.5).
- Reports state **location-based Scope 2 only**; market-based accounting, which
  differs by an order of magnitude, is not computed and says so.
- A number recalled from memory is not a citation: each coefficient names a source
  and the date it was read.

## Consequences

- **The claim the project can defend:** location-based Scope 2 for cloud egress, from
  a cited method, at the grid that served the request, with self-hosted egress
  excluded and labelled. That is a defensible claim to put in front of a
  sustainability team.
- **The claim it does not make:** measured. No wording in the product or the docs says
  otherwise, and the labels make it structurally hard to imply.
- **It costs the headline.** On the shipped all-local configuration, CO2 *and* avoided
  CO2 both read zero, because the premium baseline the avoided figure compares against
  is itself unaccounted. The dashboard prints "excluded from scope" where a green KPI
  used to be. That is the honest rendering of "we do not know".
- **It costs a benchmark metric too.** The evaluation harness refuses to publish a
  carbon-saving ratio it cannot account, and prints *not determinable* with the
  reason. The guard is not about the default: with a *mixed* registry — accounted cloud
  baseline, unaccounted local tiers — the naive ratio reads **100 % carbon saved**
  purely because the cheap tier is unmetered.
- **Provenance and boundary are two different things.** A vendor figure may be
  full-stack (Google's is: accelerator, host, idle capacity, datacenter overhead) while
  a parametric GPU-level estimate is not, so `includes-datacenter-overhead` is declared
  separately from the label and decides whether PUE applies. Deriving it from the label
  would have been one field fewer and one silent double-count.
- **Some published figures are unusable, and saying why is part of the work.**
  Mistral's LCA of Mistral Large 2 (with ADEME and Carbone 4) reports gCO2e per
  400-token response including embodied impacts. Back-converting it to kWh through an
  assumed grid intensity would be arithmetic laundering, so it is cited as context and
  explicitly not used as a coefficient.
- **What a later "lot D" would change, precisely.** Metering self-hosted inference —
  sampling RAPL / NVML / amdgpu hwmon around an inference, calibrating per model
  offline, and attributing a shared accelerator across concurrent requests — would add
  a fourth label (`MEASURED`) for local models, not edit a coefficient. It would move
  the default configuration from "excluded from scope" to a real number, and only then
  does "avoided CO2" mean anything out of the box. It would do nothing for cloud
  egress, which stays `MODELLED` or `VENDOR_PUBLISHED` until vendors publish
  per-request figures.
- **Still open, and owned elsewhere:** marginal rather than average grid intensity,
  dual location/market-based reporting, embodied (manufacturing) impacts, and an
  uncertainty interval propagated through the schema instead of a documented range.
  All four are named in
  [`../carbon-intensity-reliability.md`](../carbon-intensity-reliability.md) §5 rather
  than left implicit.
