# ADR 0010 — Trace cache decisions at the same level as routing decisions

**Status:** Accepted

## Context

v2 set out to make routing explainable: a row per request saying which tier was
chosen, by which strategy, with what justification. The semantic cache was the
open question. It is *upstream* of the router and short-circuits it, so on a hit
there is no routing decision at all — the trace's blind spot is exactly the
requests the cache answered.

Tracing it costs more than it looks. The cache decides on **every** request, hit
or miss, so a second table doubles the write volume of the tracing subsystem,
adds a second retention policy, and stores a similarity score for requests where
nothing interesting happened.

The cheaper options considered:

- **Trace routing only.** The v2 goal, literally read. Leaves cache hits with no
  explanation of any kind.
- **A flag on the routing row.** `cache_hit boolean` already exists on
  `request_log` — but a hit *has no routing row* to carry a flag, and a boolean
  says nothing about the score, the runner-up or the threshold in force.
- **Trace refusals only.** Record the interesting cases, skip the plain misses.
  Halves the volume and destroys the denominator: a false-positive rate needs the
  misses.

## Decision

A full `cache_decision` row per lookup — `HIT` / `MISS` / `BYPASS` / `ERROR`, the
winning score, the **runner-up's** score, the threshold in force, the conformal
status, and on a hit the served entry's id, age and `origin_correlation_id`.

The argument is **error-cost asymmetry**. A routing mistake sends a request to a
model that is too weak or too expensive: it costs money, and the answer is still
an answer to the question asked. A cache false positive returns **another
question's answer** to a user who has no way to know. The decision that fails in
the worse way must not be the one nobody can inspect.

The runner-up score is the second half of the decision. Filtered store-side, a
rejected candidate is invisible: a `0.93` hit whose runner-up scored `0.92` is a
coin flip, the same hit against `0.41` is not, and only the advisor-side
comparison can tell them apart. That is also why the threshold moved out of the
vector store into the advisor — and it is the data
[ADR 0008](0008-conformal-prediction-over-fixed-thresholds.md)'s calibration is
fitted on.

`origin_correlation_id` closes the loop: a hit leads back to the routing decision
that produced the answer being replayed, which is the only way a cache hit gets a
routing explanation at all.

## Consequences

- **Write volume roughly doubles.** Both recorders are the same
  `AsyncDecisionRecorder`, off the request path, never blocking and never
  throwing; failures increment `gatewai.decisions.write.failures` instead of
  failing a completion. Retention (`gatewai.decisions.retention-days`, 90) and
  the `gatewai.decisions.enabled` switch apply to both tables at once.
- **The cache became measurable, then calibrated.** Recording the near-misses is
  what made a false-positive rate computable and a conformal threshold fittable;
  without this row, batch 3 had nothing to fit the cache half on.
- **The history must be merged across two tables.** `GET /v1/admin/decisions`
  reads both, because a list built on routing rows alone omits every request the
  cache answered — the exact hole this ADR closes.
- **The asymmetry is now visible in the schema**, and it should stay legible: a
  `routing_decision` missing beside a `cache_decision` with `outcome = HIT` is
  correct, not a gap, and every reader of these tables has to know that.
- **Prompt text is still not stored** — `prompt_hash` and `prompt_length` only —
  so a traced cache decision explains *what was decided*, and the analysis behind
  it can only be recomputed from a prompt supplied again.
