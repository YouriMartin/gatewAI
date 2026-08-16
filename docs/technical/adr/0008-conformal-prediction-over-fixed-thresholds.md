# ADR 0008 — Calibrate thresholds by conformal prediction, not by tuning or Platt scaling

**Status:** Accepted

## Context

Two similarity thresholds governed every request: the semantic cache served any
candidate above `0.92`, and the router trusted a semantic route above `0.60`.
Both were guesses, and the evaluation harness (v2 batch 5) measured what they
cost: at `0.60`, **82 % of English prompts fell below the bar** and were decided
by the heuristic instead; at `0.92`, the cache served a wrong answer **16.1 %**
of the time.

Three ways to replace a guessed threshold with a fitted one were available.

- **Tune the constant on labelled data.** A grid sweep already exists in the
  harness, so this is the cheapest option — and it produces another constant,
  with no statement of what it promises. Re-tuning after every route edit is a
  ritual, not a guarantee.
- **Platt scaling / isotonic regression.** Fit a sigmoid mapping similarity to a
  probability, then threshold the probability. It needs a *parametric* assumption
  (that the score-to-probability relation is logistic) and it calibrates
  probabilities, not decisions: reading "0.86" still leaves the operator to pick
  a cut-off, which is the problem restated one level up.
- **Split conformal prediction.** Fit the quantile `q̂` of a non-conformity score
  on a held-out labelled set, admit candidates whose score is at most `q̂`.

## Decision

Split conformal prediction, fitted per decision on its own labelled half, stored
per target with its provenance (`conformal_calibration`, one row per `CACHE` /
`ROUTING`).

Two properties decided it.

**It is distribution-free and finite-sample.** The `⌈(n+1)(1−α)⌉`-th smallest
score — not the empirical `1−α` percentile — is what makes the guarantee hold at
`n = 93` labelled cases rather than asymptotically. On a hand-labelled set that
will never be large, an asymptotic guarantee is no guarantee.

**α is the thing an operator actually wants to set.** "At most 10 % of
non-servable pairs may be served" is a sentence a reviewer can accept or reject.
"Threshold 0.9423" is not. The two decisions are therefore calibrated on
**opposite classes** with their own `ConformalGuarantee`, because a cache false
positive returns another question's answer while a routing miss costs a
hand-over to the heuristic.

The output is not only a threshold but a **prediction set** — every candidate
that clears `q̂`. The cache refuses to serve an ambiguous set (more than one
candidate looks right, so at most one of them is), and the cascade escalates on
it (v2 batch 4).

## Consequences

- **The guarantee is conditional on exchangeability**, which production traffic
  does not have to honour. This is stated in
  [`conformal-calibration.md`](../conformal-calibration.md) and in
  [`limitations.md`](../../functional/limitations.md), and it is why
  `routing_config_version` and `embedding_model` stale a calibration instead of
  letting it silently describe a system that has moved.
- **Coverage is marginal, not per-request.** 90 % coverage over the distribution
  says nothing about *this* prompt. No panel is allowed to present it as a
  per-decision confidence.
- **Degradation is a first-class path, not an error.** With no valid calibration
  the fixed constants apply unchanged and the decision records
  `NOT_CALIBRATED` / `STALE_CALIBRATION`; `gatewai_conformal_calibration_stale`
  makes that visible in one alert rule.
- **The set can be uninformative.** At α = 0.10 the routing set usually holds all
  three tiers, which is why the cascade gates on the `top1 − top2` margin and not
  on set size alone (v2 batch 4, D23).
- **A labelled set is now a build artifact**, with the maintenance that implies:
  editing routes or datasets invalidates the recorded fixtures and the harness
  says so.
- Platt scaling stays available for a later, larger dataset — nothing in the
  design forbids fitting a probability first and calibrating conformally on top
  of it.
