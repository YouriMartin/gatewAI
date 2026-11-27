# ADR 0019 — Calibrate the cache on shadow labels, per client, above the threshold in force

**Status:** Accepted (v4 batch D.3). Extends [ADR 0008](0008-conformal-prediction-over-fixed-thresholds.md):
the method is unchanged, and the labels can now come from production. Builds on
[ADR 0018](0018-shadow-mode-serves-by-pass-through.md) (shadow mode) and on D.2's
`would_hit_assessment`.

## Context

The cache threshold is fitted on 93 hand-labelled non-servable pairs that ship with the
jar (`conformal-calibration.md`). Those pairs are adversarial on purpose and
single-turn. Exchangeability with a given team's traffic is the weakest assumption in
the whole guarantee, and `limitations.md` says so.

D.2 now labels production traffic. Each shadow would-hit can go to an LLM judge, and its
verdict (`YES` | `NO` | `UNSURE`) is stored in `would_hit_assessment` next to the shadow
request's `cache_decision`. That row already holds what a cache calibration needs: the
query similarity (`similarity_score`), the threshold it was decided under (`threshold`),
the runner-up and the embedding model. No text is needed and none is stored.

Three facts shape what those labels can support:

1. **The cache is already per client.** Every lookup is filtered by `client_id`
   ([ADR 0014](0014-scope-the-cache-by-conversation-context.md)). A client only ever
   hits entries its own traffic stored, so the similarity distribution a threshold
   governs is the client's own.
2. **`conformal_calibration` holds one row per target.** As it stands, a threshold
   fitted on one client's labels would govern every client.
3. **Labels exist only for would-hits.** A pair is judged only if the cache would have
   served it, which means its similarity cleared the threshold in force and its
   prediction set was a singleton. Pairs below that threshold, and ambiguous sets, are
   never labelled.

## Decision

1. **`POST /v1/admin/calibration` accepts `source=shadow`**, next to the default
   `source=labelled-set`. A shadow calibration fits the **cache only**: the judge grades
   cached answers, not routes, so the routing calibration keeps its labelled set and is
   not touched by this call.
2. **A cache calibration is per client, with a global fallback.** `conformal_calibration`
   holds one row per (target, client) (`V16`). An empty client marks the global row, and
   `ROUTING` only ever has the global row.
   - `client=<id>` fits on that client's labels and stores that client's row.
   - Without `client`, the labels of every shadow client are pooled and replace the
     global row. The fit summary (point 6) breaks the pool down by client, so the
     operator sees when one client's traffic dominates the threshold that every other
     client inherits.
   - When a request is decided, the client's own row applies if it is `VALID`,
     otherwise the global row, otherwise the fixed threshold, exactly as today. Every
     `cache_decision` records which one decided (`calibration_scope`: `CLIENT` |
     `GLOBAL` | `NONE`), so the trace never has to be reconstructed.
   - `DELETE /v1/admin/calibration/cache/{clientId}` removes a client's row and returns
     that client to the global one.
3. **Which labels count.** A label counts only when all of the following hold:
   - the judge outcome is `JUDGED` with a `YES` or `NO` verdict (`UNSURE`, `FAILED` and
     `NOT_SAMPLED` are counted in the summary, never fitted on);
   - it was judged under the current `JudgeRubric.VERSION`;
   - its similarity came from the current embedding model;
   - it was judged by **one** judge model: `judgeModel` in the request, by default
     `gatewai.shadow.judge.model`. Labels from two judges are never pooled, for the same
     reason two rubrics are not.
4. **One floor: the threshold in force for the scope being fitted.** The floor `F` is
   the threshold that currently decides that scope's requests:
   - for a client fit: that client's own row if `VALID`, otherwise the global row,
     otherwise the fixed threshold;
   - for a pooled fit: the global row, otherwise the fixed threshold.

   A label counts only if it was **recorded under a threshold ≤ `F`** and its
   **similarity is ≥ `F`**. A label recorded under a higher threshold is dropped: it is
   a draw from the pairs above *that* threshold and says nothing about the band between
   `F` and it. A label recorded under a lower threshold is cut at `F`. Every remaining
   score is then a draw from one population: the would-hits above `F`. The
   non-conformity score is unchanged (the similarity of the `NO` pairs), and so are the
   quantile rule and the 409 when the sample cannot support the α asked for.
5. **A minimum sample, or 409.** After the floor, the fit needs:
   - at least `gatewai.conformal.shadow.min-negatives` `NO` pairs, **default 50**, and
     never fewer than `ConformalQuantile.minimumSampleSize(α)`;
   - at least `gatewai.conformal.shadow.min-pairs` judged pairs (`YES` + `NO`), default
     200.

   Below either minimum, the call returns `409 calibration_failed` with the counts it
   found and the counts it needs. The `YES` pairs do not enter `q̂`. They are required
   because they measure the cost side: the summary states the share of judged-servable
   pairs the new threshold would refuse.

   The default of 50 `NO` pairs is chosen for the spread of a single fit, not for
   feasibility. The roadmap's 20 is feasible at α = 0.10 (the quantile needs 9) and
   barely at α = 0.05 (it needs 19). But the wrong-answer rate one fit actually
   delivers is random: for split conformal with continuous scores it follows
   Beta(n + 1 − k, k), with k = ⌈(n + 1)(1 − α)⌉ (Vovk, 2012; Angelopoulos & Bates,
   *A Gentle Introduction to Conformal Prediction*, arXiv:2107.07511). Computed from
   that law (modelled, not measured), at α = 0.10:

   | `NO` pairs | P(rate > 0.15) | P(rate > 0.20) |
   |---:|---:|---:|
   | 20 | 0.18 | 0.07 |
   | 50 | 0.11 | 0.02 |
   | 93 (the shipped set) | 0.05 | < 0.01 |
   | 200 | 0.01 | < 0.01 |

   At 20, roughly one fit in fourteen would serve more than twice the wrong answers it
   promises. 50 keeps that under one in fifty while staying reachable. `NO` is the rare
   class, so 50 of them takes several hundred judged would-hits. A lower minimum
   remains one property away, and the summary states the spread either way.
6. **The row states its source and keeps a summary of the fit.** New columns on
   `conformal_calibration`:
   - `source` (`LABELLED_SET` | `SHADOW`), `client_id`, and for a shadow fit
     `judge_model`, `rubric_version` and `label_floor` (`F`);
   - `fit_summary` (`jsonb`, versioned), which holds:
     - the labels read, and how many each rule of points 3–4 excluded;
     - `YES` / `NO` / `UNSURE` / `FAILED` among the rest;
     - the time span of the labels;
     - the refused-servable share;
     - the ambiguous-set count (see Consequences);
     - the 90th percentile of the Beta law above, labelled *modelled from the sample
       size*;
     - for a pooled fit, the same counts per client.

   `sample_size` keeps its meaning: the `NO` pairs `q̂` was fitted on. The labelled-set
   fit writes a summary too: the cases read, the negatives, the resource it read them
   from.
7. **Staleness rules are unchanged.** A different embedding model stales a client row
   as it stales the global one. Nothing stales a calibration because traffic drifted;
   recalibration stays explicit and manual (ADR 0008).

## Consequences

- **The guarantee changes subject, not strength.** It becomes: *of the pairs this
  judge would reject, at most α are served*. The reasoning is short. A `NO` pair below
  `F` is never served, because `q̂ ≥ F`. A `NO` pair above `F` is exchangeable with
  the calibration scores, so it is served with probability at most α. Overall the rate
  is therefore at most α · P(similarity ≥ F | NO) ≤ α. The bound is conservative, not
  weaker. Like every conformal guarantee it holds on average over fits, which is what
  the minimum of point 5 is sized for. Two caveats change its subject, and the docs
  must state both:
  - **it is the judge's `NO`, not a person's.** The judge's own error rate is not
    measured anywhere (D.2, `limitations.md`);
  - **the reference is the answer received this time**, not a correct answer.
- **The threshold ratchets up, and getting out of the ratchet takes new labels.** No
  label exists below `F`, so a shadow fit can never learn that a lower threshold would
  be safe. Each fit raises the floor of the next one.
  - **`DELETE` is the first step, not the whole exit.** It returns the client to the
    global threshold, which is now its `F`. Every label recorded under the client's old,
    higher row is above that `F`, so point 4 drops it. A fit right after the `DELETE`
    therefore has only the labels from before the client's first shadow fit, and
    usually returns 409.
  - **The exit completes once the client has been observed again.** It stays in
    `SHADOW` until the minimum sample has re-accumulated under the global threshold.
    A client already in `ENFORCE` produces no labels, so it has to be switched back to
    `SHADOW` first.
  - **The global row has its own exit.** A pooled shadow fit ratchets the global row
    the same way. `POST /v1/admin/calibration` with `source=labelled-set` overwrites it
    with the hand-labelled fit and leaves every client row as it is.
  - **Ending the ratchet itself** would take labelling near-misses (judging, on a
    sample, the best candidate below the threshold). That changes D.2, not D.3, so it
    is on the roadmap's "not scheduled" list.
- **Ambiguous sets are outside the sample.** A request whose prediction set was
  ambiguous at `F` was never served, so it was never judged. At `q̂ > F`, some of those
  sets become singletons and would be served, yet no label speaks for them. The
  stored rows show how many there are: decisions in the fitted scope with a top score
  ≥ `q̂` and a runner-up in [`F`, `q̂`). The summary records that count, so the
  operator sees it.
- **Per-client rows match how the cache already works.** The cost is a schema change
  (`V16`), a snapshot keyed by (target, client), and one more column on
  `cache_decision`. The pooled fit stays available. Its per-client breakdown is what
  makes it honest: clients that were never observed inherit a threshold whose
  population is written down.
- **A client calibrated in shadow keeps its row in `ENFORCE`.** That is the intended
  path: observe in shadow, calibrate on what was observed, then switch to enforcing
  with a threshold fitted on the client's own traffic. Once the client enforces, no new
  labels arrive (the judge runs in shadow only), so the row ages without anything
  checking it.
- **A fit stays auditable after retention, but cannot be rerun.** The labels are
  purged with the decision tables; `fit_summary` is not. What a calibration was fitted
  on (counts, exclusions, floor, span, judge, rubric, per-client breakdown) survives
  the labels that produced it. Rerunning the same call later fits on whatever labels
  are retained by then.
- **`UNSURE` pairs remain unbounded.** They are excluded: neither the rate nor the
  guarantee says anything about them, and the summary counts them beside the fit.
