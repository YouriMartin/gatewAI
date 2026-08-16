# Evaluation harness

**v2 batch 5.** The gateway takes two automatic decisions per request — *serve
this from cache?* and *which tier?* — and until now nothing said whether it took
them well. This is the labelled data, the harness that scores the real decision
code against it, and the committed baselines that make a regression fail the
build.

It ships **before** conformal calibration ([batch 3](../developpment/roadmap-v2.md))
on purpose: calibrating a threshold without an evaluation set replaces a guessed
constant with a quantile computed on nothing.

---

## What runs, and when

| | Command | Needs | Duration |
|---|---|---|---|
| **Score** (every build) | `./mvnw test` | nothing | ~0.15 s |
| **Re-record fixtures** (by hand) | `./mvnw test -Dtest=EvalFixtureRecorderTest -Deval.record=true` | nothing — the model is in-process (v3 lot A) | ~30 s |

`EvaluationHarnessTest` is an ordinary unit test. It runs on every commit, in CI,
with no Ollama, no database and no network, and writes
`target/eval/report.json` + `report.md`. CI publishes both as an artifact and
pastes the Markdown into the job summary.

## Design: real code, recorded model

The harness scores **the production classifier**, not a re-implementation of it.
`EmbeddingComplexityClassifier`, `HeuristicComplexityClassifier` and
`ClassifierProperties` are package-private, so a single test-scope class —
`EvalClassifierFactory`, deliberately in `infrastructure.llm` — builds them
exactly as Spring does, from a `RoutingConfig`. A harness that re-derived route
ranking, the similarity threshold and the hand-over to the heuristic would keep
reporting good numbers after the real router regressed.

The one thing replaced is the model server:

```
routing:  dataset → [real EmbeddingComplexityClassifier] → tier
                          │
                          └── EmbeddingModel = ReplayEmbeddingModel (recorded vectors)

cache:    dataset → recorded cosine(query, entry) → threshold policy → HIT / MISS
```

Two fixture shapes, for two different reasons:

- **`routing-vectors.json`** — 322 recorded vectors (300 prompts + the route
  examples), base64 little-endian `float32`, the exact bits the model returned.
  Not quantised: a lossy fixture would move decisions that sit near the
  threshold, which are precisely the ones worth measuring. There is real logic to
  replay here, so the vectors go in and the classifier does the rest.
- **`cache-similarities.json`** — one cosine per labelled pair. The cache's rule
  is `bestScore >= threshold`, a single comparison; the score itself comes from
  pgvector, not from gateway code. Recording similarities pins down the model's
  opinion and leaves the *policy* free to vary, which is exactly what batch 3
  calibrates.

An unknown text is a hard failure with a re-record instruction, never a zero
vector — a fabricated embedding would score as a confident wrong answer and blame
code that never changed.

### Provenance and staleness

Every fixture carries the embedding model, its dimensions, the recording
timestamp, a digest of the dataset files and the `RoutingConfigVersion` it was
recorded under. The harness asserts all four against the current tree, so editing
a prompt or a route example fails the build with the command to re-record rather
than silently scoring stale numbers. Same discipline as a persisted routing
decision in batch 2 (see [`data-model.md`](data-model.md)): numbers replayed without knowing what
produced them quietly describe something else.

**What this cannot detect:** a change in the embedding model itself. That is a
re-record plus a deliberate baseline edit — visible in the diff, which is the
point.

---

## The datasets

JSON Lines, one labelled case per line so a relabelling is a one-line diff.
Calibration and test sets are **disjoint** and asserted to be.

| File | Where | n | Content |
|---|---|---|---|
| `routing-calibration.jsonl` | `src/main/resources/eval/` | 200 | `(prompt, expectedTier, language, tags)` |
| `routing-test.jsonl` | `src/test/resources/eval/` | 100 | idem, disjoint |
| `cache-calibration.jsonl` | `src/main/resources/eval/` | 200 | `(query, entry, judgment, language, tags)` |
| `cache-test.jsonl` | `src/test/resources/eval/` | 100 | idem, disjoint |

The **calibration** halves ship in the jar (v2 batch 3): the gateway calibrates
itself from them on a fresh install, rather than shipping thresholds someone
guessed and waiting for the operator's own labels. The **test** halves stay
test-scope on purpose — a calibration fitted on its own test set measures
nothing.

Both calibration sets meet the n ≥ 200 the split-conformal method of batch 3
needs. Labels are hand-made; they are the real cost of v2 and are meant to be
argued with.

### Routing labels

The tier a competent reviewer would say the request *needs*: `LOCAL` for trivial
requests (greetings, simple facts, short translations), `CLOUD_ENTRY` for drafting
and summarising, `CLOUD_PREMIUM` for code, architecture, debugging and multi-step
reasoning — the same definitions the LLM classifier's system prompt uses, so the
three strategies are judged against one standard.

Roughly balanced across tiers, ~50/50 EN/FR, and deliberately seeded with cases
built to break specific mechanisms:

| Tag | What it probes |
|---|---|
| `keyword-trap` | contains a premium keyword but is trivial ("What does the word *algorithm* mean?") |
| `length-trap` | long, rambling and trivial — the heuristic's length rule fires wrongly |
| `short-premium` | four words, genuinely hard ("Fix this deadlock.") |
| `ambiguous` | a human could defend two tiers |
| `ood` | unlike any route example (cooking, sport, insurance) |
| `long`, `code` | exercise the length and code-fence rules |

No evaluation prompt may be a copy of a route example — that would score 1.0 for
free. A test asserts it, case-, accent- and punctuation-insensitively; it caught
five leaked prompts while the set was being written.

### Cache labels

The judgment answers one question only: **would the answer stored for `entry`
correctly and completely answer `query`?** It is not a similarity rating — two
texts can be near-identical and earn a `NO` (an entity swap), or differ widely
and earn a `YES` (a politeness wrapper).

Conventions worth stating, because they are choices:

- **Cross-lingual pairs are `NO`.** The cached answer is in the entry's language;
  serving it answers in the wrong one.
- **Volatile questions are `NO` even when `query == entry`** ("what is our current
  queue depth?"). No similarity threshold can fix these — they are a freshness
  problem, and they set an irreducible floor on the false-positive rate.
- **`format-change` pairs** ("summarise in one sentence" vs "in three bullets")
  are `YES`: the content answers, the shape does not match. Reasonable people
  disagree; the tag makes the choice visible and reversible.

Negative cases are built from the ways meaning flips under small edits:
`entity-swap`, `negation`, `direction-swap`, `version-swap`, `task-swap`,
`scope-change`, `specificity`, `near-miss`, `unrelated`.

---

## Metrics

All six metrics named in the v2 plan appear in `report.json`, and since v2
batch 4 all six are measured.

| Metric | Status |
|---|---|
| Routing accuracy | measured (per tier, tag and language, plus a confusion matrix) |
| Cache accuracy | measured (false-positive / false-negative rates + a threshold sweep) |
| Estimated savings | measured (€ and gCO2 vs an all-premium baseline) |
| Decision latency p50/p95 | recorded live at fixture time |
| Escalation rate | measured since v2 batch 4 (see below) |
| Conformal coverage | measured since v2 batch 3 (see below) |

Four deliberate choices:

- **Accuracy is split by direction.** Over-routing wastes money and carbon;
  under-routing returns an answer the chosen tier could not give. Both cost one
  point of accuracy and they are not the same mistake.
- **Savings are printed next to under-routing.** A gateway reaches 100 % carbon
  saving by sending everything to the smallest model. The report says so, in the
  savings section, with the count.
- **Decision latency cannot be measured hermetically** — replay is a hash-map
  lookup. It is measured live during recording and labelled as such.
- **The cascade's accuracy is published as a lower bound, not a number.** The
  escalation rate is exact — levels 1 and 2 and both gates are the shipped code
  — but level 3 is a model, and a hermetic run has none. It is stubbed by the
  heuristic, which is the case where escalating buys nothing, so the accuracy
  printed beside the escalation rate is the floor rather than the expectation.

The savings figure rests on two stated assumptions: prompt tokens ≈ characters/4,
and a constant 400 completion tokens per request whichever tier serves it
(holding it constant is what isolates the routing decision). It runs through the
production `CarbonCalculator` and the shipped registry coefficients, so it moves
when they move.

### Escalation rate (v2 batch 4)

Scored at five margin bands, the shipped one among them, so the default is
visibly a trade-off:

| Margin band | Escalation rate | Error capture | Accuracy (lower bound) |
|---|---|---|---|
| 0.01 | 15 % | 42 % | 81 % |
| **0.02 (shipped)** | **23 %** | **61 %** | **77 %** |
| 0.03 | 28 % | 73 % | 78 % |
| 0.05 | 38 % | 84 % | 69 % |
| 0.08 | 49 % | 92 % | 63 % |

**Error capture** is the share of the run's routing errors sitting inside the
escalated bucket — the ceiling on what escalating can fix, since the cascade can
only correct a decision it escalated. At the shipped band, 23 % of traffic holds
61 % of the errors: five times denser than the rest, so the gate is picking the
right requests rather than requests at random (asserted on every build).

The accuracy column is the pessimistic bound described above, and it is
**below** the 83 % the routes reach alone. That is not a defect of the cascade,
it is its condition of use stated numerically: handing 23 % of traffic to a
34 %-accurate classifier costs 6 points, so the cascade pays only where the
classifier model beats the heuristic on the requests it is given. The build
holds that worst case at no more than 8 points (`cascadeWorstCaseAccuracyLossMax`).

---

## Results at the time of writing

Embedding strategy, `paraphrase-multilingual-MiniLM-L12-v2` in-process (384 dim),
route threshold **0.25**, cache threshold 0.92, default routes — re-recorded in
v3 batch A.4.

| | Calibration | Test |
|---|---|---|
| Routing accuracy | 76.0 % | **81.0 %** |
| — heuristic baseline (same test set) | — | 34.0 % |
| — English / French | 71.3 % / 80.8 % | 78.4 % / 83.7 % |
| — calibrated (α = 0.10) | — | **82.0 %** |
| Over-routed / under-routed | 32 / 16 | 12 / 7 |
| Below-threshold hand-overs | 14 of 200 | 7 of 100 |
| Cache false positives | 12.9 % | 14.3 % |
| Cache false negatives | 63.6 % | 61.4 % |
| CO2 saved vs all-premium | — | 38.4 % |
| Decision latency p50 / p95 | — | **3.2 ms / 8.2 ms** |

**Semantic routing earns its keep**: 81 % against the heuristic's 34 % on the same
set, and the heuristic still scores 0 % on `keyword-trap`, `length-trap` and
`ambiguous` — the traps it was designed to fall into. That gap is the invariant
this harness exists to defend, and it survived the model swap.

Three things the v3 run changed, and one it did not:

- **Decision latency fell by an order of magnitude**, 34 ms → 3.2 ms p50. The old
  figure was an HTTP round trip to Ollama; this one is an ONNX session in the
  same JVM.
- **The threshold moved with the model** (0.60 → 0.25) because a similarity
  threshold belongs to an embedding model's scale. Kept at 0.60 the same model
  scores 40 %, handing 88 of 100 prompts to the heuristic. See
  [the candidate comparison](#choosing-the-in-process-embedding-model-v3-batch-a3).
- **The cache trades differently at the same 0.92**: fewer wrong answers
  (16.1 % → 14.3 %) for more refusals (45.5 % → 61.4 %). The constant was kept
  because correctness is the side this cache errs on, and the baselines record
  both halves of that trade.
- **The savings caveat is unchanged in kind**: 38.4 % CO2 saved comes with 7
  under-routed requests out of 100. The number is real and so is the caveat; they
  belong in the same sentence.

### What the v2 run found, and why it still matters

The v2 baseline (`nomic-embed-text`, 768 dim, over HTTP) scored **62 %** routing
accuracy at the fixed 0.60 and **83 %** calibrated, with cache FP 16.1 % / FN
45.5 % and 34 ms p50 decisions. Two of its findings are the reason later batches
exist and are worth keeping:

**1. English routed far worse than French — the opposite of the documented
risk.** 45.1 % against 79.6 %, because the mean best-route similarity was 0.538
for English against 0.647 for French, so 82 % of English prompts fell below the
0.60 bar and were decided by the heuristic. It was a **threshold** problem, not a
language-coverage problem — the argument that produced batch 3. The v3 model
shows the same lesson from the other side: the number 0.60 was never portable.

**2. Some false positives are irreducible.** At threshold 1.00 the cache
false-positive rate was still 4.3 %: the `volatile` pairs, where the same question
has a different answer today. No calibration reaches them; only a TTL or a
freshness policy does. That is a property of the labelled data, not of the model,
and it still holds.

---

## Choosing the in-process embedding model (v3 batch A.3)

Lot A moved the embedding model into the JVM, which made the model a **choice**
rather than a given. Three ONNX candidates were scored by re-recording the
fixtures with each and running this harness unchanged — same datasets, same
routes, same code, only the vectors differ. All three are int8, 384-dim.

**Routing**, test half (100 prompts), at the shipped fixed threshold and at the
threshold each model's own calibration produces:

| Candidate | Fixed 0.60 | **Calibrated** | EN | FR | Hand-overs | Mean margin | p50 / p95 |
|---|---|---|---|---|---|---|---|
| `paraphrase-multilingual-MiniLM-L12-v2` | 40.0 % | **82.0 %** | 80.4 % | **83.7 %** | 4 | **0.158** | 3.05 / 6.35 ms |
| `all-MiniLM-L6-v2` (EN-only) | 37.0 % | 73.0 % | 82.4 % | 63.3 % | 5 | 0.109 | 1.79 / 6.74 ms |
| `multilingual-e5-small` (no prefixes) | **79.0 %** | 81.0 % | 82.4 % | 79.6 % | 5 | 0.023 | 2.87 / 5.22 ms |

**Cache**, test half, at the shipped `0.92` and at each model's calibrated
threshold (α = 0.10):

| Candidate | Fixed: FP / FN / hit | Calibrated q̂ | Calibrated: FP / FN / hit |
|---|---|---|---|
| `paraphrase-multilingual-MiniLM-L12-v2` | **14.3 %** / 61.4 % / 25 % | 0.9526 | 14.3 % / 88.6 % / 13 % |
| `all-MiniLM-L6-v2` | 17.9 % / 68.2 % / 24 % | 0.9315 | 14.3 % / **75.0 %** / **19 %** |
| `multilingual-e5-small` | **76.8 %** / 9.1 % / 83 % | 0.9775 | **7.1 %** / 88.6 % / 9 % |

### The choice, and why the fastest and the sharpest both lost

**`paraphrase-multilingual-MiniLM-L12-v2` ships.** It is the most accurate
calibrated router of the three (82.0 %), the only one that does not collapse on
one language, and the only one whose confidence signal still means something.

- **`all-MiniLM-L6-v2` is out on French**, at 63.3 % against 83.7 %. It is the
  cheapest by far (23 MB against 118 MB) and the fastest at p50, and on English
  it is the equal of the winner — but the default routes are bilingual and the
  project has been measuring the language split since v2 batch 5. Twenty points
  is not a rounding error.
- **`multilingual-e5-small` is the interesting failure.** It wins outright at the
  *fixed* threshold — 79 % against 40 % — because its similarities all sit high,
  so almost nothing falls below 0.60. That same compression is what disqualifies
  it: at the shipped `0.92` cache threshold it **serves 76.8 % of the pairs a
  human labelled non-servable**, and its mean routing margin is **0.023**, which
  is the cascade's shipped ambiguity band. A model whose top-1 and top-2 routes
  are always within the band escalates **56 %** of requests (against 10 % for the
  winner) — the confidence signal that batches 3, 4 and 8 are built on stops
  discriminating. It was measured *without* the `query:` / `passage:` prefixes it
  expects, which is how the gateway would actually run it; adding them would also
  break the shared-vector assumption of
  [ADR 0007](adr/0007-memoized-embedding-model.md).

Two things this measurement forces into the open, both for batch A.4:

1. **The shipped `route-similarity-threshold=0.60` is wrong for the new model.**
   It was tuned on `nomic-embed-text`'s scale; on the winner it hands **88 of 100**
   prompts to the heuristic, which is why the fixed column reads 40 %. The
   calibrated threshold is **0.2221**. An uncalibrated v3 gateway routes *worse*
   than an uncalibrated v2 one, and the default has to move with the model.
2. **The cache trade is harsher than it was.** At α = 0.10 the winner refuses
   88.6 % of servable pairs for a 14.3 % wrong-answer rate; on `nomic-embed-text`
   the same α gave 65.9 % / 12.5 %. The dial is `gatewai.conformal.cache-alpha`
   and it now costs more hit rate per point of correctness.

Against v2's numbers (`nomic-embed-text`, 768 dim, over HTTP): calibrated routing
**83.0 % → 82.0 %**. One point of accuracy is what the network hop was worth —
paid back in a gateway that starts with no model server, at 3 ms per decision.

---

## Conformal calibration (v2 batch 3)

The harness fits both calibrations on the **calibration** halves and scores them
on the **test** halves, which is the acceptance criterion of batch 3 verified
hermetically on every commit:

| Target | Guarantee | α | Threshold | Promised | Measured | 1 s.e. |
|---|---|---|---|---|---|---|
| routing | `CORRECT_TARGET_COVERAGE` | 0.10 | 0.4588 | 90 % coverage | **93.0 %** | 3.0 % |
| cache | `WRONG_ANSWER_RATE` | 0.10 | 0.9423 | ≤ 10 % wrong answers | **12.5 %** | 4.0 % |

Both land within two standard errors of their promise, which is the tolerance
the assertions use — a finite test set cannot land exactly on a marginal
guarantee, and asserting equality would be asserting that a coin lands on its
expectation.

The report also scores the calibrated classifier end to end
(`routingTestCalibrated`, `cacheTestCalibrated`), so the improvement is visible
next to the fixed-threshold run rather than claimed: routing 62 % → 83 %, cache
false positives 16.1 % → 12.5 %.

The quantile and the route scoring are the production domain classes; what the
harness does not replay is the calibration service's plumbing — reading labelled
files, embedding pairs, storing the fit — which has its own unit test. Replaying
that here would mean recording a vector for every cache text, tripling the
fixtures to re-verify a cosine. The live service was checked against the
hermetic fit and produces the same thresholds to the digit.

## Baselines and regressions

`src/test/resources/eval/baselines.json` holds a floor (or ceiling) per metric,
set just below the measured run. Since the harness replays fixed fixtures, a drop
between two commits means the **decision code** changed its mind about prompts it
used to get right — there is no flakiness to absorb, and the margin only exists
so a rounding difference does not fail a build.

Raising a baseline after a genuine improvement is the intended way to ratchet.
Lowering one should be as awkward to justify in review as it sounds.

The harness also asserts, independently of any threshold:

- the calibration sets are large enough to calibrate on (n ≥ 200);
- calibration and test are disjoint, and ids unique;
- no prompt is a route example in disguise;
- fixtures match the current datasets, routing rules and embedding model;
- **the embedding strategy still beats the heuristic it falls back to** — if the
  extra embedding call stops paying for itself, that is worth failing a build
  over.

## Limits of the exercise

- **300 labelled prompts and 300 pairs by one author.** Enough to detect a
  regression and to calibrate a threshold; not enough to claim a production
  accuracy figure, and not independent of the person who wrote the routes.
- **The corpus is EN/FR only** and skewed toward software questions, which is the
  gateway's own use case but not everyone's.
- **Scoring is against labels, not against answers.** "Correct tier" means "the
  tier a reviewer says it needs", not "the tier that produced an acceptable
  answer". Measuring the latter needs the models in the loop and a judge, which is
  a different batch.
- **The fixtures freeze one model.** Everything here is conditional on
  `nomic-embed-text`; another embedding model is another recording and another
  set of numbers.
