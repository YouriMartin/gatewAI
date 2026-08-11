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
| **Re-record fixtures** (by hand) | `docker compose up -d ollama` then `./mvnw -Pit test -Dtest=EvalFixtureRecorderTest -Deval.record=true` | Ollama + `nomic-embed-text` | ~30 s |

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
  will calibrate.

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

`src/test/resources/eval/`, JSON Lines, one labelled case per line so a
relabelling is a one-line diff. Calibration and test sets are **disjoint** and
asserted to be.

| File | n | Content |
|---|---|---|
| `routing-calibration.jsonl` | 200 | `(prompt, expectedTier, language, tags)` |
| `routing-test.jsonl` | 100 | idem, disjoint |
| `cache-calibration.jsonl` | 200 | `(query, entry, judgment, language, tags)` |
| `cache-test.jsonl` | 100 | idem, disjoint |

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

All six metrics named in the v2 plan appear in `report.json`. Two are emitted as
`null` with the reason attached rather than omitted, so the report's shape stops
changing and the gaps stay visible.

| Metric | Status |
|---|---|
| Routing accuracy | measured (per tier, tag and language, plus a confusion matrix) |
| Cache accuracy | measured (false-positive / false-negative rates + a threshold sweep) |
| Estimated savings | measured (€ and gCO2 vs an all-premium baseline) |
| Decision latency p50/p95 | recorded live at fixture time |
| Escalation rate | `null` — no cascade to escalate through until batch 4 |
| Conformal coverage | `null` — no calibrated prediction set until batch 3 |

Three deliberate choices:

- **Accuracy is split by direction.** Over-routing wastes money and carbon;
  under-routing returns an answer the chosen tier could not give. Both cost one
  point of accuracy and they are not the same mistake.
- **Savings are printed next to under-routing.** A gateway reaches 100 % carbon
  saving by sending everything to the smallest model. The report says so, in the
  savings section, with the count.
- **Decision latency cannot be measured hermetically** — replay is a hash-map
  lookup. It is measured live during recording and labelled as such.

The savings figure rests on two stated assumptions: prompt tokens ≈ characters/4,
and a constant 400 completion tokens per request whichever tier serves it
(holding it constant is what isolates the routing decision). It runs through the
production `CarbonCalculator` and the shipped registry coefficients, so it moves
when they move.

---

## Results at the time of writing

Embedding strategy, threshold 0.60, cache threshold 0.92, `nomic-embed-text`,
default routes.

| | Calibration | Test |
|---|---|---|
| Routing accuracy | 66.0 % | 62.0 % |
| — heuristic baseline (same test set) | — | 34.0 % |
| Over-routed / under-routed | 12 / 56 | 4 / 34 |
| Cache false positives | 19.4 % | 16.1 % |
| Cache false negatives | 54.2 % | 45.5 % |
| CO2 saved vs all-premium | — | 55.4 % |
| Decision latency p50 / p95 | — | 34 ms / 44 ms |

Four findings, none of which were visible before this batch existed.

**1. English routes far worse than French — the opposite of the documented
risk.** 45.1 % accuracy on English against 79.6 % on French. The cause is not the
classifier but the threshold: the mean best-route similarity is **0.538 for
English prompts against 0.647 for French**, so **82 % of English prompts fall
below the 0.60 threshold** (French: 14 %), hand over to the heuristic, and the
heuristic's default rule sends short prompts to `LOCAL`. Twenty-seven of the 38
test misses are exactly that path. [`routing.md`](routing.md) warned that
`nomic-embed-text` is English-centric and that French was therefore the risk; on
this data the risk is the other way round, and it is a **threshold** problem, not
a language-coverage problem. That is a strong argument for batch 3 calibrating
`route-similarity-threshold` on data rather than shipping 0.60 as a guess.

**2. The 55 % carbon saving is partly under-routing, not efficiency.** 34 of 100
test requests went to a cheaper tier than their label. The number is real and so
is the caveat; they belong in the same sentence.

**3. The cache refuses about half of what it could serve.** At 0.92, false
negatives are 45.5 % on the test set — every one a model call that did not need
to happen — while false positives are still 16.1 %. The sweep shows the shape of
the trade-off:

| Threshold | FP (calibration) | FN (calibration) | Hit rate |
|---|---|---|---|
| 0.85 | 49.5 % | 18.7 % | 66.5 % |
| 0.90 | 26.9 % | 43.0 % | 43.0 % |
| **0.92** (shipped) | **19.4 %** | **54.2 %** | **33.5 %** |
| 0.95 | 8.6 % | 75.7 % | 17.0 % |

There is no threshold that makes both small — the distributions overlap
(servable pairs: median 0.910; non-servable: median 0.850). This is the batch 3
argument in one table: a single global threshold is the wrong instrument, and α
should be asymmetric because a false positive returns another question's answer
to a user while a false negative costs one local inference.

**4. Some false positives are irreducible.** At threshold 1.00 the false-positive
rate is still 4.3 %: the `volatile` pairs, where the same question has a different
answer today. No calibration reaches them; only a TTL or a freshness policy does.

**Semantic routing does earn its keep**: 62 % against the heuristic's 34 % on the
same set, and the heuristic scores 0 % on `keyword-trap`, `length-trap` and
`ambiguous` — the traps it was designed to fall into.

---

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
