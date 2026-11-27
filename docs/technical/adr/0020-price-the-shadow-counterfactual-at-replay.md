# ADR 0020 — The shadow report prices the counterfactual at replay time and stores it

**Status:** Accepted (v4 batch D.4). Builds on [ADR 0018](0018-shadow-mode-serves-by-pass-through.md)
(shadow mode), D.2's wrong-hit estimate, [ADR 0019](0019-calibrate-the-cache-on-shadow-labels.md)
and the per-row provenance of v3 lot C.5.

## Context

D.4 is the report a team reads before turning enforcement on: for a shadow client over
a period, what it paid against what it would have paid, the CO2 on both sides, the
would-hit rate, the estimated wrong-hit rate and the routing mix gatewAI would have
applied. The roadmap's acceptance is strict: **every figure re-derives from stored
rows**, the exports state their basis and scope on their face (never a bare zero),
and a golden test pins the result on a fixed dataset.

What is stored today does not support "what it would have paid":

- **The actual side is stored.** A shadow request is served by pass-through and
  accounted like any request. Its `request_log` row holds the cost, the energy, the
  CO2 and the provenance it was priced with, all computed when it was written
  (v3 C.5, v4 B.6).
- **The counterfactual side is not.** The replay records what the cache and the router
  decided: `cache_decision` and `routing_decision`, stamped `SHADOW`. Nothing records
  what that decision would have cost. Pricing it at report time would read the
  **current** registry: today's prices, today's regions, today's energy sources. A
  report of last month would then change when a price is edited, and the figures
  would no longer re-derive from the rows of the period.
- **`request_log` does not say a row was a shadow request.** A client's mode can change
  during a period, and a replay that was dropped (over `gatewai.shadow.max-concurrent`)
  leaves no decision row. So "requests observed" cannot be counted from the decision
  tables.

## Decision

1. **The replay prices its counterfactual and stores it.** When a shadow evaluation
   completes, it writes one `shadow_counterfactual` row (`V17`), joined to the request
   on `correlation_id`. The row is priced by the same `GreenAccountant` and provenance
   code as `request_log`, at the same moment the replay decides. It is self-describing
   the way a `request_log` row is: model, provider, grid zone and intensity, energy
   source, pricing basis and currency, as they were.
2. **What "would have" means, per outcome.** The counterfactual follows the chain's
   own verdict, never a guess:
   - `WOULD_HIT`: served from the cache. No inference, so cost and energy are zero, as
     an enforced cache hit is accounted today.
   - `ROUTED`: a miss, sent to the model the router chose (the routed registry id the
     replay already carries). It is priced at that model's prices and site.
   - `PASSED_THROUGH`: a request the chain cannot honour. It would have been passed
     through anyway (ADR 0018, point 4), so the counterfactual is the actual: same
     model, same cost.
3. **Same token counts, stated.** The routed model never ran, so its usage is not
   known. The counterfactual uses the usage the client's answer reported: prompt,
   completion and cached prompt tokens. Every report states this as the basis of the
   would-have figures. This is *modelled*, never *measured*: another model would have
   tokenised and answered differently.
4. **`request_log.mode`** (`V17`, `ENFORCE` | `SHADOW`, like the decision tables since
   `V14`) marks the requests served in shadow. "Observed" counts these rows.
   "Evaluated" counts those with a counterfactual. The difference (dropped, failed,
   no text answer) is reported as **not evaluated**. It is never counted as zero
   cost, and it is left out of both sides of the comparison.
5. **One report, from stored rows only.** `GET /v1/reports/shadow?from&to&client`
   (`format=json|csv|pdf`) aggregates:
   - `request_log` (paid) and `shadow_counterfactual` (would have paid) over the
     evaluated requests;
   - `cache_decision` and `routing_decision` stamped `SHADOW` for the would-hit rate
     and the routing mix (would-hits as a `cache` bucket, pass-throughs as
     `pass-through`);
   - `would_hit_assessment` for the wrong-hit estimate (D.2: Wilson 95 %, the judge
     label, UNSURE counted beside it).

   The registry, the pricing configuration and the calibration in force are not read.
6. **Every figure travels with its basis.** On both sides, cost is reported per
   currency with its pricing basis (B.6), and CO2 with its emissions scope
   (`ALL_ACCOUNTED` … `ALL_EXCLUDED`, v3 C.1) and the location-based Scope 2 note. A
   would-have saving is **not netted** against the estimated wrong-hit rate: they are
   two figures, side by side, each with its own uncertainty.
7. **Who may read it.** An admin reads any client, or all shadow clients pooled. Any
   other key reads its own client's report only. The report carries a per-client
   wrong-hit estimate, which D.2 keeps admin-only across clients.

## Consequences

- **A report of a past period stays what it was.** Editing a price, a region or an
  energy source changes the next counterfactuals, not the stored ones. This is the
  rule `request_log` has followed since v3 C.5, extended to the side that never ran.
- **The replay does a little more work**: one accounting computation and one insert
  per evaluated request. It stays inside the shadow permit (ADR 0018, point 5), so
  under load it drops like the rest of the evaluation rather than queuing.
- **The saving is an estimate in three ways, and the report says all three:**
  - the token counts are borrowed from the answer received;
  - a would-hit's saving assumes the cached answer was acceptable, which the
    wrong-hit estimate, shown beside it, questions;
  - the routing mix is the router's verdict on this traffic, not on what a team would
    have sent had it known.
- **Requests from before `V17` have no counterfactual and no mode.** A period that
  starts before the migration reports them as not evaluated rather than inventing
  them.
- **Pricing a model that has left the registry** cannot happen at replay time,
  because the router only picks registered models. A row priced by a model since
  removed keeps the price it was given.
- **Retention.** `shadow_counterfactual` is purged with the decision tables. A report
  of a purged period has `request_log` rows and no counterfactuals, so it reports them
  as not evaluated, never as zero.
