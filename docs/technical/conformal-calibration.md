# Conformal calibration

**v2 batch 3.** The gateway had two magic numbers: the cache served anything
above `0.92` similarity, and the router trusted a route above `0.60`. Neither
came from data. This batch replaces both with quantiles fitted on labelled cases,
each carrying a stated guarantee, provenance, and an automatic fall back to the
old constants when it stops being true.

It ships **after** the evaluation harness ([`evaluation.md`](evaluation.md)) on
purpose: calibrating without a way to measure the result is how you replace a
guess with a more confident guess.

Why conformal prediction rather than a tuned constant or Platt scaling:
[ADR 0008](adr/0008-conformal-prediction-over-fixed-thresholds.md).

---

## The method: split conformal prediction

Fit, offline, on labelled cases:

1. score each calibration case with a **non-conformity score** `s_i`;
2. take `q̂ = the ⌈(n+1)(1−α)⌉-th smallest score`;
3. at inference, admit a candidate when its score is at most `q̂`.

The `n+1` is the whole point, and the reason
[`ConformalQuantile`](../../src/main/java/io/github/yourimartin/gatewai/domain/model/ConformalQuantile.java)
is not a call to `percentile(scores, 1-alpha)`. It is the finite-sample
correction that turns an empirical percentile into a distribution-free
guarantee: the next observation is treated as the `(n+1)`-th member of an
exchangeable sample, so its rank among the others is uniform. Drop it and the
guarantee only holds asymptotically — which, on 200 hand-labelled cases, means it
does not hold.

When `⌈(n+1)(1−α)⌉ > n`, the sample cannot support that α **at all**, and the
calibration fails loudly with the number of cases it would need. It does not
quietly return the largest score it happens to hold.

## Two decisions, two guarantees, deliberately asymmetric

The plan asked for α to be chosen asymmetrically. It is more than the value that
differs: the two decisions are calibrated **on opposite classes**, because their
errors cost completely different things. The `ConformalGuarantee` enum names
which promise a stored calibration carries, so one `alpha` field can never be
read as the same promise twice.

| | Routing | Cache |
|---|---|---|
| Guarantee | `CORRECT_TARGET_COVERAGE` | `WRONG_ANSWER_RATE` |
| Fitted on | the **positive** class: `1 − similarity` to the correct tier's route | the **negative** class: the similarity of pairs a human judged *not* servable |
| α means | share of prompts whose correct route may fall outside the set | share of non-servable pairs that may be served anyway |
| Threshold | `1 − q̂` | `q̂` |
| Cost of the error it bounds | a hand-over to the heuristic | **another question's answer, returned to a user** |
| Default α | 0.10 | 0.10 |

Guaranteeing *coverage* on the cache would have controlled the cheap error (a
needless model call) and left the expensive one free. That is the wrong way
round, so the cache is calibrated on the pairs that must never be served.

## What it changed

Measured by the batch 5 harness, fitting on the calibration halves and scoring
on the disjoint test halves (100 prompts, 100 pairs):

| Routing | Fixed 0.25 | Calibrated 0.2221 |
|---|---|---|
| Accuracy | 81.0 % | **82.0 %** |
| English / French | 78.4 % / 83.7 % | **80.4 %** / 83.7 % |
| Below-threshold hand-overs | 7 of 100 | **4 of 100** |
| Over- / under-routed | 12 / 7 | 13 / 5 |
| Empirical coverage (target 90 %) | — | **91.0 %** (1 s.e. 3.0 %) |

| Cache | Fixed 0.92 | Calibrated 0.9526 |
|---|---|---|
| Wrong answers served (FP) | 14.3 % | **14.3 %** (target ≤ 10 %, 1 s.e. 4.0 %) |
| Refused but servable (FN) | 61.4 % | 88.6 % |
| Hit rate | 25.0 % | 13.0 % |

> Numbers re-measured in **v3 batch A.4** on the in-process
> `paraphrase-multilingual-MiniLM-L12-v2` (384 dim). On the previous model
> (`nomic-embed-text`, 768 dim) the same method gave routing 62 % → 83 % at a
> calibrated 0.4588, and cache FP 16.1 % → 12.5 % at 0.9423. A q̂ belongs to the
> model that produced the similarities — which is exactly what the
> `embedding_model` column is for.

Three things worth saying plainly:

- **The routing gain is the threshold, not the prediction set.** This is the
  batch's most reusable finding, and v3 proved it twice. On the old model, 82 %
  of English prompts scored below the guessed 0.60 and were decided by the
  heuristic; calibrating to 0.4588 removed almost all of those hand-overs and
  doubled English accuracy. On the new model the guess was wrong again and in the
  same direction — 88 of 100 prompts below 0.60 — so v3 batch A.4 moved the
  *fixed fallback* to 0.25 as well. A similarity threshold is a property of an
  embedding model, never a constant to carry between them. What calibration adds
  on top is the last two points (81 % → 82 %) **and** a stated guarantee.
- **The routing prediction set is not a decision on its own.** At α = 0.10 it
  usually contains all three tiers — 70 of 100 test prompts — so the router takes
  the top-ranked route and records the set as evidence. v2 batch 4 made the set
  act, and had to measure exactly this to do it: escalating whenever the set is
  not a singleton, as planned, would have called the classifier model for 70 % of
  requests. The cascade therefore reads the set *and* the margin — empty
  escalates, a singleton decides, several tiers escalate only inside a margin
  band — which lands at 10 % escalation on the current model (23 % on the model
  batch 4 measured). Details in
  [`routing.md`](routing.md#cascade-opt-in--cascadecomplexityclassifier-v2-batch-4).
  The set is still worth its column: singletons are right 93 % of the time
  against 79 % for the rest.
- **The cache trades hit rate for correctness.** Fewer wrong answers means more
  model calls, which for a gateway that exists to save carbon is a real cost, not
  a free win. The dial is `gatewai.conformal.cache-alpha` and its consequences
  are measured, which is the improvement over a constant nobody could justify.

### What the old constants were implicitly choosing

On the v2 labelled set and model, `α = 0.20` yielded a cache threshold of
**0.9203** — the guessed 0.92, almost exactly. The old constant was not wrong so
much as *unstated*: it was accepting a ~20 % wrong-answer rate, and nobody had
written that down.

The v3 model rearranges the same trade: at the unchanged 0.92 it serves fewer
wrong answers (14.3 %) and refuses far more servable pairs (61.4 %), and its
α = 0.10 quantile lands at **0.9526**. The constant stayed because correctness is
the side this cache errs on deliberately — but note what it costs now, 13 % hit
rate against 22 % on the old model. That is the number to watch if the cache's
carbon saving matters more than its precision in a given deployment.

### A floor no threshold can reach

Any cache α at or below ≈ **0.054** degenerates to a threshold of 1.0, which
serves almost nothing. The reason is four labelled pairs that ask the *same
volatile question twice* ("what is our current queue depth?") and therefore score
exactly 1.000 while being labelled non-servable. No similarity threshold can
exclude them; only a TTL or a freshness policy can. Since `4/93 ≈ 4.3 %` of the
negative sample is irreducible, α must sit above it.

This is the kind of thing a calibration tells you and a guessed constant never
does: the mechanism has a floor, and it is 4 %.

## Using it

```bash
# What is in force right now, and why
curl -H "Authorization: Bearer $ADMIN_KEY" localhost:8080/v1/admin/calibration

# Fit both calibrations from the labelled set (~15 s on a local stack)
curl -X POST -H "Authorization: Bearer $ADMIN_KEY" localhost:8080/v1/admin/calibration

# Or at explicit risk levels
curl -X POST -H "Authorization: Bearer $ADMIN_KEY" \
     -H 'Content-Type: application/json' \
     -d '{"routingAlpha":0.10,"cacheAlpha":0.20}' \
     localhost:8080/v1/admin/calibration
```

Recalibration is **explicit and never automatic**. It embeds every labelled pair
and scores every labelled prompt, and — more importantly — a threshold that
governs what users are served should move when a person decides it should, not
when a scheduler fires. A sample too small for the α asked for returns `409` with
the number of cases it would need.

The gateway ships the calibration halves of the evaluation data
(`classpath:/eval/*-calibration.jsonl`, 200 prompts + 200 pairs) so the button
works on a fresh install. Point `gatewai.conformal.routing-cases` /
`cache-cases` at your own labelled traffic — any Spring resource location — to
calibrate on it instead. The **test** halves stay out of the jar on purpose: a
calibration fitted on its own test set measures nothing.

## Invalidation and degradation

A calibration is a statement about a specific embedding model and, for routing, a
specific set of route examples. When either changes, the number it computed
describes a system that no longer exists.

| Change | Routing | Cache |
|---|---|---|
| Different embedding model | `STALE` | `STALE` |
| Route examples edited (`PUT /v1/admin/routing`) | `STALE` | unaffected — pair similarity has nothing to do with routes |
| `gatewai.conformal.enabled=false` | `DISABLED` | `DISABLED` |
| Never calibrated | `ABSENT` | `ABSENT` |

In every non-`VALID` case the **fixed configured threshold applies**, a warning
is logged once per transition, and
`gatewai_conformal_calibration_stale{target}` goes to 1. Degradation is silent
in the responses by construction, so it is made loud everywhere else.

The store is read at most once a minute into an in-memory snapshot: the request
path never queues on a query, and a database outage costs the *trace*, not the
thresholds — the last snapshot keeps applying.

## Limits

- **The guarantee is marginal, not conditional.** "At most 10 % of non-servable
  pairs are served" is a statement about the population, not about your request.
  A calibration cannot tell you that *this* answer is right.
- **It assumes exchangeability** between the calibration cases and production
  traffic. The shipped labels are deliberately adversarial — entity swaps,
  negations, direction swaps are over-represented relative to real traffic — so
  the measured error rates should be read as a **worst case**, and the guarantee
  transfers only as far as that resemblance holds. Calibrating on your own
  traffic is the fix, and is one property away.
- **n = 200 and n = 93.** The cache is fitted on 93 negative pairs, which makes
  α = 0.10 about the tightest honest promise available; the finite-sample rule
  refuses anything the sample cannot support, but a small sample still means a
  wide confidence interval around the empirical rate.
- **One embedding model.** Every number here is conditional on the model that
  produced the vectors. The v2 numbers below were fitted on
  `nomic-embed-text`; v3 lot A replaced it with an in-process ONNX model at a
  different width, which stales both calibrations by construction — the
  `embedding_model` column exists for exactly this, and v3 batch A.4 re-fits
  them.

These also appear in [`../functional/limitations.md`](../functional/limitations.md),
at the same level of honesty as the energy coefficients.

## Where it lives

| Piece | Class |
|---|---|
| The quantile, with its finite-sample rule | `domain/model/ConformalQuantile` |
| A fitted threshold + provenance + staleness | `domain/model/ConformalCalibration` |
| What α promises | `domain/model/ConformalGuarantee` |
| Route scoring shared by router and calibration | `domain/model/RouteScoring` |
| Fitting, snapshotting, degradation | `application/service/ConformalCalibrationService` |
| Storage (one row per target) | `conformal_calibration` table, `JpaCalibrationStore` |
| Labelled cases (replaceable port) | `infrastructure/calibration/ClasspathLabelledCaseSource` |
| Admin API | `adapter/in/web/AdminCalibrationController` |
| Gauges + startup report | `infrastructure/metrics/CalibrationMetrics` |

`RouteScoring` is worth a note: the calibration must be fitted on exactly the
scores the router decides with, and the first version achieved that by asking the
router — which produced a bean cycle (calibration → classifier → calibration).
Sharing the *scoring* as domain code instead gives the same guarantee with the
dependencies flowing one way.
