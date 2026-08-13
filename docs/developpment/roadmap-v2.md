# gatewAI v2 — Explainable, calibrated decisions

**Action plan.** v1 covers Phases 0–9 (see
[`plan-action-green-ai-proxy.md`](plan-action-green-ai-proxy.md)). v2 turns the
gateway's three automatic per-request decisions — *serve from cache?*, *which
tier?*, *what carbon cost?* — into decisions that are **traced, replayable and
calibrated on data** instead of on guessed constants.

This plan is derived from an implementation spec (revision 2, 2026-08-10) and
was **re-checked against the code**. Section 1 lists every point where the spec
and the repository disagree; those corrections are folded into the batches.
Items pulled in from [`roadmap-post-v1.md`](roadmap-post-v1.md) are marked
**[post-v1]**.

---

## 1. Verified repo state and deviations from the spec

Everything the spec asserts about the architecture holds — advisor order, three
classifiers behind a `@Primary` delegate, in-memory route index, pgvector used
by the cache only, `ddl-auto=update`, Micrometer + Checkstyle + SpotBugs. The
following details do **not** hold and change the work.

| # | Spec assumption | Verified reality | Consequence |
|---|---|---|---|
| D1 | The shared request embedding is a plumbing change | `SearchRequest` (Spring AI 2.0) exposes **only a `String` query** — no precomputed-vector entry point. `PgVectorStore.getQueryEmbedding` calls `embeddingModel.embed(query)` internally (`PgVectorStore.java:391`) | Passing a vector into the cache would mean bypassing `VectorStore`, which [ADR 0005](../technical/adr/0005-depend-on-vectorstore-interface.md) forbids. **Solution: memoize at the `EmbeddingModel` level** — see 0.2 |
| D2 | A correlation id exists (OTel) | `RequestContext(clientId, traceId)` exists, but `traceId` is bound to **`null`** (`ApiKeyAuthenticationFilter.java:62`) and nothing ever sets it. **No tracing dependency in `pom.xml`** (no `micrometer-tracing`, no OTel) | `correlation_id` is a new capability, not a field to read. New batch **0.3**. OTel spans (Lot 6 of the spec) require a new dependency — treated as optional |
| D3 | `CLIENT_PINNED` traces an existing behaviour | `RoutingAdvisor.adviseCall` classifies **unconditionally** on any non-blank user text and always rewrites the model id (`RoutingAdvisor.java:44-68`). A client cannot pin a model today; `routing.md` overstates this | Either implement real pinning (recommended, see 4.2) or drop the enum value. Tracing a reason that can never occur is worse than not having it. **Settled in batch 4: implemented** |
| D4 | `runner_up_score` is free | `gatewai.cache.top-k=1` and `similarity-threshold=0.92` are applied **inside the store** | With top-k=1 there is no runner-up, and with a 0.92 floor a runner-up below threshold is never returned. Observing the cache margin honestly requires top-k ≥ 2 and moving the threshold decision into the advisor — see 2.2 |
| D5 | `ClassificationResult` name collision | Confirmed: package-private `record ClassificationResult(ModelTier tier, String reasoning)` in `infrastructure.llm`. Note the field is `reasoning`, not `reason` | New return type named **`ClassificationOutcome`**; the LLM justification carries `reasoning` verbatim |
| D6 | `carbon_record_ref` points at the carbon record | `RequestLog.id` is a `UUID` generated in `ChatCompletionService` **after** the call; advisors never see it. `RequestLog` has no correlation column | The join key must be the correlation id: add `correlationId` to `RequestLog` + `RequestLogEntity`, and reference *that*, not a FK to `request_log.id` |
| D7 | Integration tests with Testcontainers extend what exists | **No Testcontainers and no Flyway in `pom.xml`**. DB-touching tests today are Spring slices and smoke tests | Both are new dependencies with their own CI cost. Scoped explicitly in 0.1 and 5.3 |
| D8 | Metric names like `gatewai_routing_decisions_total` | The existing recorder registers **dotted** names (`gatewai.requests`, `gatewai.cache.hits`, …); Micrometer renders the Prometheus form | Register dotted names. Also `gatewai.cache.hits`/`.misses` already exist — the new outcome-tagged counter must not silently duplicate them |
| D9 | `MetricsRecorder` can carry the new metrics | The out port has a single method `record(RequestLog)` | Decision metrics need a second method or a sibling out port; the domain must stay Micrometer-free (ArchUnit) |
| D10 | — | `roadmap-post-v1.md` claims `adviseStream` "just delegates" | **Stale**: `SemanticCacheAdvisor.adviseStream` already short-circuits with a synthetic `Flux` (Phase 7.5). Fix that bullet |

Two further constraints that no batch may break:

- **ArchUnit.** New decision types that the domain touches (`ClassificationOutcome`,
  justifications, decision records) live in `domain/model` with **zero Spring, JPA
  or Spring AI imports**. JSONB mapping, Jackson annotations and Micrometer stay
  in `infrastructure`.
- **Native image.** The `native` profile is expected to keep working: every new
  record serialized to JSONB or over the admin API needs reflection hints in
  `NativeRuntimeHints` — a v1 capability that is easy to silently regress
  **[post-v1]**.

---

## 2. Scope

**In:** uniform explanation contract across the three classifiers; persistence of
routing **and cache** decisions; versioning under hot configuration; conformal
calibration of the cache and routing thresholds; calibrated cascade routing; an
evaluation set and CI metrics; occlusion attribution and counterfactuals as
on-demand endpoints; extension of the existing Micrometer stack; a "why this
decision" panel in the dashboard.

**Out:** mechanistic interpretability (SAE, circuit tracing, steering);
fine-tuned/trained classifier (stays a separate backlog item); explaining the
*target model's output* — v2 explains **the gateway's decisions**; automatic
recalibration in production (calibration stays a triggered operation).

### Guiding principles

1. **Extend, don't duplicate.** Carbon accounting, the routing advisor, the
   observability stack and the dashboard exist.
2. **The hot path stays hot.** Occlusion and counterfactuals are computed on
   demand on dedicated endpoints, never synchronously.
3. **No robustness regression.** Routing already survives an embedding failure by
   falling back to the heuristic. Every addition preserves that property — DB
   down, calibration stale, embedding service down.
4. **A guessed threshold is debt.** Every magic constant is replaced by a
   calibrated value or justified in an ADR.
5. **No plaintext prompts in the database.** Hashes and metadata. Plaintext
   storage, if needed for replay, is opt-in with configurable retention —
   consistent with the guardrails/PII backlog axis **[post-v1]**.
6. **A feature without a metric is decorative.** Batch 5 gates batches 3 and 4.

---

## Batch 0 — Prerequisites (blocking) — ✅ done

Two corrections found while implementing, both worth carrying forward:

- **D11 — an uncached request embedded the text three times, not twice.** The
  plan counted the cache search and the router. It missed the third: on a miss
  the cache stores its entry as `new Document(userText, …)`, which embeds the
  same text again. All three now share one vector.
- **D12 — Flyway needs Boot 4's `spring-boot-flyway` module.** `flyway-core`
  alone puts Flyway on the classpath with **no auto-configuration**, so the app
  starts, silently skips every migration, and then fails schema validation. Boot
  4 ships auto-configurations in per-technology modules.

### 0.1 Versioned migrations **[post-v1]** — ✅

Move from `ddl-auto=update` (`application.properties:61`) to **Flyway**, with a
baseline migration for the existing schema (`request_log`, `api_client`).

Delivered: `V1__baseline.sql` (`request_log` + `api_client`) and
`V2__request_log_correlation_id.sql`, `ddl-auto=validate`. Spring AI keeps
owning `vector_store` and the `vector` extension, so ADR 0005 holds. `V1` is
idempotent and Flyway runs with `baseline-on-migrate=true` /
`baseline-version=0`, so an existing `ddl-auto=update` database upgrades with no
manual step.

Verified against a real Postgres 17 + pgvector, both paths: a fresh database
(2 migrations applied, validation passes) and a simulated pre-v2 database
(baselined at 0, `V1` replayed as a no-op, existing rows preserved, `V2` adding
the new column). The hand-written baseline was checked against the DDL
`ddl-auto=update` actually produces — identical types and nullability.

Once Flyway exists, the persistent deferred-job store becomes cheap **[post-v1]** —
noted here, not scheduled.

### 0.2 Shared request embedding **[post-v1]** — ✅

Today an uncached request embeds **twice**: once inside the cache's
`VectorStore.similaritySearch`, once in `EmbeddingComplexityClassifier`.

**Decision (D1): a memoizing `EmbeddingModel` decorator**, not a new port. A
`@Primary` decorator wraps the Ollama `EmbeddingModel` and memoizes
`embed(String)` in a `ScopedValue`-bound per-request map. Because `PgVectorStore`
embeds the query through that very bean, the cache and the router both hit the
memo — one embedding per request, **`VectorStore` untouched, ADR 0005 intact**.

The memo entry carries the embedding model id and version; that is what later
lets an explanation be flagged `STALE`. Cap the memo (a handful of entries) so
occlusion, which embeds many variants, cannot grow it without bound.

Delivered as `MemoizingEmbeddingModel` + `RequestEmbeddingMemo`, bound by
`SpringAiLlmClient` around the advisor chain; rationale and the assumption it
rests on are in [ADR 0007](../technical/adr/0007-memoized-embedding-model.md).
Measured on the local stack: one uncached `/v1/chat/completions` now triggers
**1** `POST /api/embed` against Ollama, down from 3 (per D11).

### 0.3 Correlation id (new — see D2) — ✅

Generate a correlation id at ingress (honouring an inbound `X-Request-Id` /
`traceparent` when present), bind it into `RequestContext.traceId`, propagate it
through the deferred path (`DeferredChatService` already re-binds the context),
and **add `correlationId` to `RequestLog` + `RequestLogEntity`** so carbon,
cache and routing records join on one key (D6). Return it as a response header.

Adding `micrometer-tracing` + OTel is a **separate, optional** decision — the
correlation id above is enough for everything v2 needs.

Delivered as `CorrelationIdFilter` (honours an inbound `X-Request-Id`, sanitizes
it, generates a UUID otherwise, always echoes it back), read by
`ApiKeyAuthenticationFilter` into `RequestContext`, persisted as
`request_log.correlation_id`. On the deferred path the job id is the correlation
id. Verified end to end: a client-supplied id and generated ids both reach the
database, on cache hits and misses alike.

---

## Batch 1 — Uniform explanation contract — ✅ done

One correction found while implementing:

- **D13 — a fallback needs two justifications, not one.** The plan modelled the
  fallback as a flat shape (`strategy`, `fallbackFrom`, `cause`). But on a
  below-threshold hand-over *two* things are worth keeping: what actually decided
  (the heuristic rule) and what the strategy that stepped aside had computed
  first (the route scores). Collapsing them makes `strategy()` lie about who
  decided. `Fallback` therefore carries `effective` **and** `evidence`, the
  latter null when the failed strategy produced nothing (an error, no routes).

Also delivered: `ClassifierStrategy` moved from `infrastructure.llm` to
`domain/model` as **`ClassificationStrategy`** — a justification has to name a
strategy, and the domain cannot import infrastructure. One enum, not two.

The explanation must exist whatever `gatewai.classifier.strategy` is set to,
otherwise it vanishes on the first config change.

### 1.1 Enrich the `ComplexityClassifier` port

`ModelTier classify(String)` becomes `ClassificationOutcome classify(String)`
(D5), where `ClassificationOutcome = (ModelTier tier, ClassificationJustification
justification)`. `ClassificationJustification` is a **sealed interface** in
`domain/model` with one variant per strategy:

- **Heuristic** — rule + observed value:
  `{ strategy: HEURISTIC, rule: PREMIUM_KEYWORD, matchedKeyword: "vulnérabilité" }`,
  `{ rule: LENGTH_THRESHOLD, observed: 640, threshold: 500 }`, `{ rule: CODE_FENCE }`.
- **Embedding** — per-route scores, margin, threshold:
  `{ strategy: EMBEDDING, candidates: [{route, tier, bestUtterance, score, rank}],
  topScore, margin, threshold }`. `bestUtterance` costs nothing: selection is
  already max-over-utterances — but `bestMatch` currently keeps only the global
  best, so it must accumulate a per-route best (still one pass, no extra
  embedding).
- **LLM** — `{ strategy: LLM, reasoning: "…", classifierModelId: "…" }`
  (`reasoning`, per D5).
- **Fallback** — effective strategy differs from the configured one:
  `{ strategy: HEURISTIC, fallbackFrom: EMBEDDING, cause: EMBEDDING_UNAVAILABLE }`.

Fallback causes to distinguish, because the three already exist in code:
`NO_ROUTES_CONFIGURED`, `BELOW_THRESHOLD`, `EMBEDDING_ERROR` (and, for the LLM
classifier, `NO_TIER_RETURNED` / `LLM_ERROR`).

### 1.2 Constraints

- **Zero cost on the nominal path**: this is data already computed and thrown away.
- `DelegatingComplexityClassifier` propagates the justification without
  reinterpreting it.
- **No routing behaviour change in this batch.** `RoutingAdvisor` reads
  `outcome.tier()` and is otherwise untouched.
- Blast radius: 1 port, 4 implementations, 1 advisor, ~5 test classes.

### Acceptance — all met

All three strategies produce a non-empty justification (asserted by iterating
`ClassificationStrategy.values()`, so a future strategy cannot skip it); the
fallback case is distinguishable from the nominal one; unit tests per strategy.

Verified against real infra that routing behaviour is **unchanged**: the three
default routes still send a refactoring prompt to `qwen2.5:3b`, a greeting to
`qwen2.5:0.5b` and a summary request to `qwen2.5:1.5b`.

---

## Batch 2 — Decision persistence — ✅ done

Three corrections found while implementing:

- **D14 — `decision_reason` had a value that could never fire and lacked one
  that always could.** `AMBIGUOUS_ESCALATED` and `CLIENT_PINNED` are not
  reachable until batch 4 builds the behaviour behind them, so they are not in
  the enum yet: a reason code that can never fire is worse than no reason code,
  and adding an enum value later is free (it is stored as a string). Conversely
  the plan missed a path that fires today — the router classifies a tier for
  which the registry holds **no model** and passes the request through. That is
  `NO_MODEL_FOR_TIER`.
- **D15 — `RequestEmbeddingMemo` had to move to `domain/model`.** The cache
  tracer needs the embedding-model provenance, the memo lived in
  `infrastructure.llm`, and ArchUnit forbids one adapter depending on another.
  It now sits beside `RequestContext` and `CarbonZoneContext`, which are in the
  domain for exactly this reason — cross-adapter request context, JDK types only.
- **D16 — a cache hit needs a second correlation id.** The plan asked that a hit
  lead back to the originating request without naming the field. It is
  `origin_correlation_id`, stamped into the vector-store metadata when the entry
  is written; `correlation_id` alone identifies the request being *served*, which
  is not the one that produced the answer.

The conformal columns (batch 3) and `escalated_to` (batch 4) are deliberately
absent: they arrive with the code that writes them rather than sitting in the
schema as columns nothing fills.

### 2.1 `RoutingDecision`

`id`, `correlation_id`, `created_at` · `prompt_hash`, `prompt_length` (no
plaintext) · `embedding_model`, `embedding_model_version` · `routing_config_version`
(see 2.3) · `strategy`, `effective_strategy` · `justification` (JSONB) ·
`chosen_tier`, `chosen_model_id` · `decision_reason` · `conformal_set`,
`conformal_alpha` (nullable, batch 3) · `escalated_to` (nullable, batch 4) ·
`routing_latency_ms` (decision only, excluding the LLM call).

`decision_reason` ∈ `MATCH` · `AMBIGUOUS_ESCALATED` · `BELOW_THRESHOLD_FALLBACK` ·
`CLIENT_PINNED` · `ERROR_FALLBACK`. **`CLIENT_PINNED` is unreachable until 4.2
lands (D3)** — either implement pinning or drop the value.

No `carbon_record_ref` column: the carbon record is reached through
`correlation_id` (D6).

### 2.2 `CacheDecision`

The cache sits **upstream** of the router: on a hit there is no routing decision
at all, and today the trace is blind exactly where user-facing risk is highest.

`id`, `correlation_id`, `created_at` · `prompt_hash` · `outcome` ∈
`HIT | MISS | BYPASS | ERROR` · `similarity_score`, `threshold` ·
`matched_entry_id`, `matched_entry_age_seconds` (from the existing `created_at`
document metadata) · `runner_up_score` · `conformal_status` (nullable, batch 3) ·
`embedding_model_version`.

Per D4, `runner_up_score` requires **`top-k` ≥ 2** and moving the accept/reject
comparison from `SearchRequest.similarityThreshold` into the advisor (query with
a lower floor, decide in code). This is also a prerequisite for batch 3, which
must see rejected candidates to calibrate on them. `BYPASS` covers the blank-text
short-circuit and a disabled cache.

On a `HIT`, the correlation id of the **originating** request should be
recoverable — store it in the cached document's metadata at write time so the
whole chain stays auditable end to end.

### 2.3 Versioning under hot configuration

`RoutingConfig` is editable in production via `PUT /v1/admin/routing`. An
explanation rebuilt after a route change does not explain the original decision.

- `routing_config_version` = stable hash over `RoutingConfig` (strategy,
  thresholds, premium keywords, routes and their examples). The record is already
  a value object with list normalization in its compact constructor, so a
  canonical hash is straightforward.
- Recomputed on every update through `RoutingConfigService` /
  `ClassifierRoutingConfigAdapter`, not only at startup.
- Log every version change with its timestamp, to correlate a metric drift with a
  config edit.
- Any explanation rebuilt under a different version is marked `STALE`, **never
  silently recomputed**.

### 2.4 Non-blocking persistence

Asynchronous writes. A persistence failure logs and increments a counter and
**never** fails the request. Configurable retention (suggested default 90 days)
and a purge task — reuse the existing `@Scheduled` setup rather than adding one.

### Acceptance — all met

Verified end to end against a live Postgres, not only in unit tests:

- **Every request produces a `CacheDecision`, and a `RoutingDecision` except on a
  cache hit.** Three requests → 3 cache decisions, 2 routing decisions; the
  repeat was a hit and never reached the router.
- **A hit is auditable back to the answer's own routing.** The `HIT` row carried
  `origin_correlation_id = b2-refactor`, the request that wrote the entry, plus
  `runner_up_score = 0.334` against a winning 1.0 — the margin that only exists
  because the threshold moved into the advisor (D4).
- **A hot route edit changes `routing_config_version` without a restart.**
  `PUT /v1/admin/routing` → the next decision was recorded under a new version,
  with the change logged and timestamped.
- **A broken decision store preserves nominal service.** With both decision
  tables renamed out from under the running gateway, a completion still returned
  **HTTP 200**, the green accounting still recorded the request, and
  `gatewai_decisions_write_failures_total` incremented for both kinds.

---

## Batch 5 — Evaluation (before batch 3, deliberately) — ✅ done

Calibrating without an evaluation set replaces a guessed threshold with a
quantile computed on nothing.

Three corrections found while implementing:

- **D17 — recording similarities would have measured a copy of the router.** The
  plan left the fixture shape open. Recording *similarities* for routing would
  have forced the harness to re-implement route ranking, the threshold and the
  hand-over to the heuristic — and to keep reporting good numbers after the real
  classifier regressed. Routing therefore records **vectors** (exact `float32`,
  not quantised) and replays the production classifier through them; only the
  cache, whose rule is a single comparison against a score the vector store
  produces, records similarities.
- **D18 — the harness has to reach into `infrastructure.llm`.** The classifiers
  and `ClassifierProperties` are package-private, correctly. One test-scope
  class, `EvalClassifierFactory`, lives in that package and builds them exactly
  as Spring does. It is the only such exception and says so in its Javadoc.
- **D19 — a savings figure without an under-routing figure is propaganda.** A
  gateway reaches 100 % carbon saving by sending everything to the smallest
  model. The report prints the two together, always.

### 5.1 Datasets — ✅

600 hand-labelled cases in `src/test/resources/eval/`, JSON Lines, one case per
line: routing `(prompt, expected_tier, language, tags)` 200 + 100, cache
`(query, entry, judgment, language, tags)` 200 + 100. Calibration and test are
disjoint and asserted to be; both calibration sets meet n ≥ 200.

Adversarial cases are tagged so they can be scored separately: `keyword-trap`
(premium keyword, trivial request), `length-trap` (long and trivial),
`short-premium` (four words, genuinely hard), `ambiguous`, `ood`, plus
`cross-lingual` and `volatile` on the cache side. A test asserts no evaluation
prompt is a copy of a route example — it caught five while the set was written.

### 5.2 Metrics — ✅ (four of six at the time; all six since batch 4)

Routing accuracy, cache false-positive/false-negative rates, estimated savings
and decision latency were measured here. Escalation rate and conformal coverage
were emitted as `null` with their reason attached — batch 4 and batch 3
respectively — so the report's shape stopped changing and the gaps stayed
visible. Batch 3 filled in coverage, batch 4 the escalation rate.

Routing accuracy is split by **direction**: over-routing wastes money and carbon,
under-routing returns an answer the chosen tier could not give. Same point of
accuracy, different mistake.

### 5.3 Automation — ✅

`EvaluationHarnessTest` runs in the ordinary `./mvnw test` in ~0.15 s, with no
Ollama and no database, and fails the build below the floors in
`baselines.json`. Fixtures carry their provenance (embedding model, dataset
digest, `RoutingConfigVersion`) and the harness refuses stale ones with the
re-record command. CI uploads `target/eval/report.{json,md}` and pastes the
Markdown into the job summary.

### What it found

| | Calibration | Test |
|---|---|---|
| Routing accuracy (embedding) | 66.0 % | 62.0 % |
| Routing accuracy (heuristic baseline) | — | 34.0 % |
| Over- / under-routed | 12 / 56 | 4 / 34 |
| Cache false positives / negatives | 19.4 % / 54.2 % | 16.1 % / 45.5 % |
| CO2 saved vs all-premium | — | 55.4 % |
| Decision latency p50 / p95 | — | 34 ms / 44 ms |

Four findings, all of which change what batches 3 and 4 should do:

1. **English routes far worse than French — the opposite of the documented
   risk.** 45 % against 80 %. Not a classifier problem but a threshold one: mean
   best-route similarity is 0.538 for English against 0.647 for French, so 82 %
   of English prompts fall below 0.60 (French: 14 %), hand over to the heuristic,
   and get sent to `LOCAL`. `routing.md` warned that `nomic-embed-text` being
   English-centric made *French* the risk; on this data it is the other way
   round. **This is the strongest argument yet for batch 3 calibrating
   `route-similarity-threshold` instead of shipping a guess.**
2. **Much of the 55 % carbon saving is under-routing**, not efficiency: 34 of 100
   test requests went below their labelled tier.
3. **No cache threshold makes both errors small** — the servable and
   non-servable distributions overlap (medians 0.910 and 0.850). At 0.92 the
   cache refuses 46 % of what it could serve. Batch 3's asymmetric α is the right
   instrument, and the sweep in the report is its input.
4. **~4 % of cache false positives are irreducible by any threshold**: the
   `volatile` pairs, where the same question has a different answer today. A TTL
   fixes those; similarity cannot.

Method, labelling conventions and limits:
[`../technical/evaluation.md`](../technical/evaluation.md).

---

## Batch 3 — Conformal calibration — ✅ done

The differentiating batch. It applies **to the cache first**, then to routing.

Its inputs existed thanks to batch 5: the labelled sets, the threshold sweep,
and the finding that no single cache threshold makes both error types small.

Four corrections found while implementing:

- **D20 — "choose α asymmetrically" is not a value, it is a side.** Fitting the
  cache on the positive class (coverage of servable pairs) would have controlled
  the *cheap* error — a needless model call — and left the expensive one, a wrong
  answer served to a user, free. The cache is therefore calibrated on the
  **negative** class, so α bounds the wrong-answer rate directly. The two
  promises are named in the stored calibration (`ConformalGuarantee`), because
  one `alpha` field that means two different things is a trap.
- **D21 — asking the router for its scores creates a bean cycle.** The plan's
  requirement that the calibration be fitted on exactly the numbers the router
  decides with was first met by calling the classifier — which then depends on
  the calibration for its threshold. Spring refused to start, and rightly. The
  ranking moved into the domain as `RouteScoring`, shared by both, which gives
  the same guarantee with the dependencies flowing one way. Only a context
  refresh catches this: `ContextLoadsTest` (integration-tagged) would have, the
  default unit suite did not.
- **D22 — the labelled set has an irreducible floor, and it is 4 %.** Any cache
  α at or below ≈ 0.054 degenerates to a threshold of 1.0. Four labelled pairs
  ask the same *volatile* question twice, score exactly 1.000, and are labelled
  non-servable; no similarity threshold can exclude them. The default α is 0.10,
  above the floor and documented as such.
- **D23 — the routing prediction set is not yet a decision.** At α = 0.10 the set
  usually holds all three tiers, so the router still takes the top-ranked route
  and records the set as evidence. What actually moved routing is the
  **threshold**. Making the set act is batch 4's cascade; the column is filled
  now so that batch has data the day it lands.

### 3.1 Method: split conformal prediction — ✅

`ConformalQuantile` implements `q̂ = the ⌈(n+1)(1−α)⌉-th smallest score` and
**refuses** rather than approximating when the sample cannot support α, naming
the number of cases it would need. Calibrations persist with their guarantee,
`alpha`, `n`, embedding model, routing config version and date, one row per
target.

### 3.2 Cache first — ✅

Prediction-set semantics as specified: empty → miss, singleton → hit, more than
one → **refuse**. Ambiguity rejection applies only under a valid calibration, so
an uncalibrated install keeps exactly its previous behaviour.

α = 0.10 on the negative class (D20) gives a threshold of **0.9423**: wrong
answers served drop from 16.1 % to **12.5 %** on the disjoint test set, at the
cost of a hit rate falling from 33 % to 22 %. Worth recording: α = 0.20 on this
data reproduces the guessed 0.92 almost exactly — the old constant was
implicitly accepting a ~20 % wrong-answer rate, unstated.

### 3.3 Routing — ✅

The 0.60 threshold is now calibratable and calibrated: **0.4588** at α = 0.10,
fitted on 200 labelled prompts. Below-threshold hand-overs to the heuristic fall
from 47 to 5 per 100 requests and accuracy goes **62 % → 83 %** (English
45 % → 90 %, French 80 % → 76 %). Empirical coverage on the disjoint test set is
93.0 % against a promised 90 % (1 s.e. = 3.0 %).

The margin `top1 − top2` is recorded per decision (batch 1) and reported by the
harness; it becomes the cascade's gate in batch 4, where the prediction set
starts acting (D23).

### 3.4 Limits to document — ✅

Marginal not conditional, exchangeability, the adversarial skew of the labels
(which makes the measured rates a worst case), n = 93 negatives, one embedding
model — in [`../functional/limitations.md`](../functional/limitations.md) and
[`../technical/conformal-calibration.md`](../technical/conformal-calibration.md).

### 3.5 Invalidation and degradation — ✅

`STALE` on an embedding-model change (both targets) or a route edit (routing
only — a cache pair's similarity has nothing to do with routes), `DISABLED` on
the switch, `ABSENT` before the first fit. In every case the fixed threshold
applies, a warning is logged once per transition, and
`gatewai_conformal_calibration_stale{target}` goes to 1. A database outage keeps
the last snapshot rather than silently moving a threshold.

### Acceptance — all met

Verified end to end against a live Postgres and Ollama, not only in unit tests:

- **Recalibration through the protected endpoint.** `POST /v1/admin/calibration`
  fitted both targets in **14 s** and returned thresholds identical, to the
  digit, to the hermetic harness fit — two independent embedding runs agreeing.
- **Empirical coverage on a disjoint test set.** Routing 93.0 % against a
  promised 90 %; cache wrong-answer rate 12.5 % against a promised ≤ 10 %. Both
  within two standard errors, asserted on every build.
- **The decisions carry their calibration.** A live request recorded
  `conformal_set = CLOUD_PREMIUM,LOCAL,CLOUD_ENTRY` with `conformal_alpha = 0.10`;
  its repeat was served from cache with `conformal_status = SINGLETON` at
  threshold 0.9423 and the originating correlation id intact.
- **Degraded mode.** `PUT /v1/admin/routing` on one route example flipped ROUTING
  to `STALE` within a second: threshold back to 0.60, warning logged, gauge at 1
  — while CACHE stayed `VALID` at 0.9423, as it should.

---

## Batch 4 — Calibrated cascade routing — ✅ done

The evolution already planned in
[`../technical/routing.md`](../technical/routing.md), with batch 3's gates.

Three corrections found while implementing:

- **D24 — the prediction set is not a gate; the set *and* the margin are.** The
  plan said "escalate when level 2's conformal set is not a singleton". Measured
  on the labelled test set at α = 0.10, that fires on **70 of 100 prompts**: the
  set usually holds all three tiers, exactly as D23 had recorded. A cascade that
  calls the model for 70 % of requests is a cost, not a saving. The shipped gate
  keeps the set where it discriminates (empty → escalate, singleton → decide —
  singletons are right 93 % of the time against 79 % for the rest) and settles
  the ambiguous case on the **margin** batch 3 said would become the gate:
  escalate only when `top1 − top2 < cascade-margin-band`. At the shipped 0.02
  that is 23 % escalation holding 61 % of the errors.
- **D25 — pinning is the absence of a classification, so it gets no
  justification.** A `Pinned` variant of the sealed hierarchy would have carried
  nothing that `chosen_model_id` and `chosen_tier` do not already hold, and
  would have forced `strategy()` — "the strategy that decided" — to return null
  or a fake enum value. `decision_reason = CLIENT_PINNED` with a null
  justification says the same thing; `RoutingDecision` now states the invariant
  (justification null **exactly when** the reason is `CLIENT_PINNED`), so batch
  1's "an explanation can never go missing" keeps a single, named exception.
- **D26 — the ambiguity band must not be part of `routing_config_version`.**
  Every other routing knob is, and the band changes outcomes, so it looked like
  it belonged. It does not: that version exists to detect when a fitted
  calibration stops describing the system, and the band changes no similarity.
  Including it would mark the routing calibration `STALE` — a 14 s refit — every
  time an operator tuned a knob the calibration does not depend on. It is
  therefore `gatewai.classifier.cascade-margin-band` (config, documented), and
  admin-API exposure moves to batch 9 with the rest of the routing UI work.

### 4.1 The cascade — ✅

`CASCADE` is a value of `ClassificationStrategy`, dispatched in
`DelegatingComplexityClassifier` — the seam designed for it — reusing the three
classifiers unchanged. Level 1 is the heuristic's *certain* subset through a new
`deterministicSignal()` entry point (code fence, premium length, blank), never
`classify()`: the premium keywords are a guess about intent, which is what the
routes do better, and the heuristic's `LOCAL` default is a fallback rather than a
signal.

The gate is `ConformalPredictionSet.escalates` in the domain, shared with the
`RoutingAdvisor` so the set recorded on a decision is by construction the set it
escalated on. The rule is identical calibrated or not — the calibration moves the
threshold the set is built on, not how the set is read — so an uncalibrated
gateway cascades on the fixed band and records `NOT_CALIBRATED`, which is the
acceptance criterion "uses the fixed band and says so".

An embedding **outage** is not an ambiguity: with no route scores at all, level 2
has already handed over to the heuristic and the cascade stops there rather than
buy a model call for an outage.

Each level reached is recorded in `routing_decision.escalated_to` (migration
`V5`) and counted in `gatewai_cascade_escalations_total{to_level}` (renamed from
the batch-4 name in batch 6), so the
escalation rate is observable in production and not only in the harness.

### 4.2 Client pinning (D3) — ✅ implemented

A client naming a **registered** model id gets it, unclassified, traced as
`CLIENT_PINNED`. An **unregistered** id is still routed: the egress has no
fallback provider, so honouring it would turn a routed request into a 400.
`gatewai.classifier.client-pinning=false` makes routing mandatory again. This
makes the gateway a plain proxy for callers who know what they want — and makes
what `routing.md` already claimed true, which is what D3 asked for.

### Acceptance — all met

- **Existing strategies stay selectable and unchanged** — `embedding` remains
  the default; the cascade is opt-in.
- **The escalation rate is exposed** — in the decision table, in Prometheus, and
  in the evaluation report, swept across five margin bands with the shipped one
  among them and asserted against a committed ceiling.
- **Without a valid calibration the cascade uses the fixed band and says so.**

### What the numbers say, including the uncomfortable part

At the shipped band, escalation targets errors: 23 % of traffic holds 61 % of the
routing errors, five times the density of the rest — asserted on every build, so
a gate that started picking requests at random would fail it.

What the harness **cannot** say is whether escalating fixes them. Level 3 is a
model and a hermetic run has none, so it is stubbed by the heuristic: the case
where escalating buys nothing. That floor is 77 % against 83 % for the routes
alone — handing 23 % of traffic to a 34 %-accurate classifier costs 6 points.
The cascade pays exactly where the classifier model beats the heuristic on the
requests it is given, and the build holds the worst case at ≤ 8 points
(`cascadeWorstCaseAccuracyLossMax`). Publishing that bound rather than a
flattering single number is the point of having a harness at all.

---

## Batch 6 — Observability — ✅ done

Extension of the Micrometer/Prometheus stack in place — no parallel mechanism.
Names registered **dotted** (D8); the Prometheus rendering is shown for
reference.

Two corrections found while implementing:

- **D27 — `gatewai.conformal.set.size` is routing-only.** The plan tags it by
  target, but the cache's prediction set is over candidate *documents* and its
  size beyond one is never recorded — batch 3 chose `conformal_status`
  (`EMPTY_SET` / `SINGLETON` / `AMBIGUOUS`) precisely because the shape is what
  matters there. Emitting a cache size would have meant inventing a number
  nothing measured, so the status rides as a tag on
  `gatewai.cache.decisions` instead and the summary stays routing-only.
- **D28 — there was no Grafana dashboard to add a panel to.** The stack
  provisioned Grafana empty, so "add a drift panel to the existing dashboard"
  had no target: dashboards lived in whatever the last person clicked together.
  The batch therefore ships the dashboard as a **committed file**
  (`docker/grafana/dashboards/gatewai-decisions.json`) with datasource and
  dashboard provisioning, which is also what makes a panel change reviewable.

### 6.1 Decision metrics — ✅

| Meter (registered) | Prometheus | Type | Tags |
|---|---|---|---|
| `gatewai.routing.decisions` | `gatewai_routing_decisions_total` | counter | `tier`, `reason`, `strategy` |
| `gatewai.routing.margin` | `gatewai_routing_margin` | summary | `tier` |
| `gatewai.cascade.escalations` | `gatewai_cascade_escalations_total` | counter | `to_level` |
| `gatewai.cache.decisions` | `gatewai_cache_decisions_total` | counter | `outcome`, `conformal_status` |
| `gatewai.cache.similarity` | `gatewai_cache_similarity` | summary | — |
| `gatewai.conformal.set.size` | `gatewai_conformal_set_size` | summary | `target` (routing, D27) |
| `gatewai.routing.config.changes` | `gatewai_routing_config_changes_total` | counter | — |
| `gatewai.conformal.calibration.stale` | `gatewai_conformal_calibration_stale` | gauge | `target` (kept from batch 3) |

Per D9, decision metrics got a sibling out port — `DecisionMetricsRecorder`,
fed the same `RoutingDecision` / `CacheDecision` objects that are persisted, so
a series cannot drift from the row it aggregates. It is called from the advisor
and the cache tracer rather than from `AsyncDecisionRecorder`, because decision
persistence is switchable (`gatewai.decisions.enabled=false`) and turning the
trace off must not also blind the dashboards.

The batch-4 counter `gatewai.classifier.cascade.level` is **removed**, not kept
in parallel: it counted the same events as `gatewai.cascade.escalations` from
the classifier instead of from the decision. One source of truth, and the
cascade classifier no longer touches Micrometer at all.

`gatewai.cache.decisions{outcome}` **supersedes** `gatewai.cache.hits` /
`.misses`, which distinguished neither a bypass, nor a failed lookup, nor a
deliberate refusal on an ambiguous set — all three were "a miss". Both are
emitted for one release, the deprecation and the migration query are in
[`../technical/observability.md`](../technical/observability.md), and the
bundled dashboard already uses the new one.

### 6.2 Drift panel — ✅

The tier mix as a **share**, with routing-config edits overlaid as bars. A mix
that moves at an edit is the edit; a mix that moves while the edit series stays
flat is the incoming traffic drifting, which re-reading the configuration will
never explain. That is why `gatewai.routing.config.changes` exists — a log line
cannot be graphed beside the mix, a counter can. The margin panel beside it is
the same signal earlier: decisions holding steady while margins collapse.

Verified live, not only in unit tests: the stack was booted from
`docker-compose.observability.yml`, and Grafana provisioned the datasource and
the dashboard on first start with no provisioning errors.

### 6.3 Tracing — deferred, as planned

OTel span attributes (`chosen_tier`, `decision_reason`, `effective_strategy`, a
**bucketed** margin — never the raw value, cardinality) remain conditional on
adopting a tracing dependency (D2). The correlation id already joins a request
to its decision rows, so the aggregate questions are answered by the meters
above and the per-request ones by the tables.

---

## Batch 7 — Occlusion attribution — ✅ done

Which parts of a prompt carried its routing decision: embed the prompt, find the
route it matched, then embed it again with each segment removed and see what the
similarity loses. Method, cost and limits in
[`../technical/attribution.md`](../technical/attribution.md).

Three corrections found while implementing:

- **D29 — the cache key needs the routing config version.** The plan keys
  reports on `(prompt_hash, embedding_model_version)`, which is not enough: an
  attribution decomposes the similarity to *the matched route's closest
  example*, so editing a route — or its examples — changes what the numbers are
  even about. Without that third component a cached report keeps explaining a
  decision the gateway no longer takes. Same reasoning as
  `routing_config_version` on a decision row, and the opposite of D26's
  conclusion for the cascade band: there, the knob changed no similarity; here,
  it changes the very quantity being decomposed.
- **D30 — the JDK cannot split the sentences a gateway actually receives.**
  `BreakIterator` (the locale-aware option on a JVM without ICU) only breaks
  before a capital letter, so `"refactor this service. add tests."` is one
  sentence to it — and one segment is not an attribution. Segmentation is
  therefore four passes: line breaks, `BreakIterator`, terminators it walked
  past, then clauses when a segment is too long. Its opposite quirk, breaking
  after "Dr.", is accepted and documented rather than patched with per-language
  abbreviation lists.
- **D31 — "rate-limit the endpoint" has no endpoint yet.** The plan puts
  `POST /v1/admin/decisions/explain` in batch 9, so the rate-limit requirement
  moves there with it (`RateLimitFilter` currently covers
  `POST /v1/chat/completions*` only). What bounds the cost meanwhile is
  structural: the segment cap, the bounded LRU cache, and the fact that nothing
  computes an attribution unless a caller asks.

### 7.1 Method — ✅

`Occlusion` (domain) does the arithmetic — contributions, normalization over the
positive ones, ranking — with no embedding in sight, so it is tested on numbers
chosen by hand rather than against whatever a model happens to output. Negative
contributions keep their sign: a segment whose removal *raises* the similarity
was pulling away from the matched route, which is a finding.

`PromptSegmentation` (domain) carries **offsets**, not just text: occluding by
substring search would remove the wrong copy whenever a sentence repeats. Above
the cap, adjacent segments are grouped rather than dropped, so the attributions
cover the whole prompt instead of a sample of it.

### 7.2 Cost — ✅

n + 1 embedding calls, capped at `gatewai.attribution.max-segments` (20), run
concurrently on a virtual-thread executor (not structured concurrency, still a
preview feature). Reports are cached in a **bounded** LRU keyed as per D29 —
bounded because prompts are user input and an unbounded map keyed by prompt is a
memory leak with a plausible name. It is a separate port from the batch-0.2
per-request memo, as the plan required, for exactly the lifetime reason it gave.

### 7.3 Applicability — ✅

Only `embedding` and `cascade` decide by similarity, so only they have
similarity to attribute; `heuristic` and `llm` return
`NOT_APPLICABLE_STRATEGY` **without embedding anything**. An empty prompt and an
empty route list are statuses too. A genuine embedding failure is not: it
propagates, because this runs off the request path and an admin asking why is
owed an error rather than a plausible-looking report.

### Acceptance

Use case, domain method and cache ship with unit tests (bag-of-words embedder,
so every expected similarity is computable by hand). **Not reachable over HTTP
until batch 9**, which is where the plan puts the endpoint.

---

## Batch 8 — Counterfactuals

Route examples are indexed **in memory**, not in pgvector, so the search is local
and nearly free — no SQL.

For each of the top non-chosen routes (limit 3): nearest utterance and similarity
gap with the chosen route. Rendered as *"the request would have gone to tier X had
it looked more like ⟨example⟩"*.

Returned examples come from route **configuration**, never from user data —
assert this explicitly in a test, since the same index would happily hold either.

---

## Batch 9 — API and dashboard

**`GET /v1/admin/decisions/{correlationId}`** — the raw persisted decision, cache
and routing, no recomputation.

**`POST /v1/admin/decisions/explain`** — input: `decisionId`, or `{prompt}` for an
on-the-fly explanation. Response shape:

```json
{
  "cache": { "outcome": "MISS", "similarityScore": 0.0, "threshold": 0.0 },
  "routing": {
    "chosenTier": "LOCAL", "chosenModelId": "…",
    "decisionReason": "MATCH",
    "strategy": "EMBEDDING", "effectiveStrategy": "EMBEDDING",
    "justification": { },
    "confidence": { "topScore": 0.0, "margin": 0.0, "conformalSet": [], "alpha": 0.05 }
  },
  "attribution": [ { "segment": "…", "contribution": 0.0 } ],
  "counterfactuals": [ { "tier": "…", "nearestUtterance": "…", "delta": 0.0 } ],
  "carbon": { "correlationId": "…" },
  "provenance": {
    "embeddingModelVersion": "…", "routingConfigVersion": "…",
    "calibrationDate": "…", "status": "VALID | STALE"
  }
}
```

`provenance` is **not optional**. `carbon` **references** the existing record
(by correlation id, D6); it does not reproduce it. Endpoints live under
`/v1/admin/**`, already covered by admin auth — add a test asserting they are not
reachable with a non-admin key, and add reflection hints for the new records so
the native profile keeps building **[post-v1]**.

**Dashboard**: a "why this decision" panel — recent decision history, detail on
click, with confidence, attribution and counterfactuals. The Svelte dashboard and
hot route editing already exist, which is what makes this demonstrable rather
than theoretical.

---

## Batch 10 — Documentation (continuous)

**Create**: `docs/technical/decision-tracing.md` (decision model, versioning,
replay) · ~~`docs/technical/conformal-calibration.md`~~ ✅ shipped with batch 3
(method, the asymmetric guarantees, results, degradation, limits) ·
~~`docs/technical/evaluation.md`~~ ✅ shipped with batch 5 (datasets, harness,
fixtures, metrics, findings) · ~~`docs/technical/attribution.md`~~ ✅ shipped
with batch 7 (occlusion method, segmentation, cost, limits).

**Update**: `routing.md` — "Future work: cascade routing" becomes implemented ·
`semantic-cache.md` — calibrated threshold, traced decision, top-k change ·
`data-model.md` — the two new tables and the `request_log.correlation_id` column ·
`observability.md` — new meters and the cache-counter deprecation ·
`testing-and-quality.md` — Flyway, Testcontainers, the evaluation task ·
~~`limitations.md`~~ ✅ marginal (not individual) coverage (batch 3), approximate
additivity of occlusion (batch 7), dependence on calibration-set exchangeability · `api-reference.md` —
the two new endpoints · `roadmap-post-v1.md` — mark done: cascade routing, shared
embedding, versioned migrations, threshold feedback loop; and fix the stale
streaming bullet (D10).

**ADRs**: (1) conformal prediction over a fixed threshold or Platt scaling ·
(2) occlusion over gradients — JVM constraint, no access to embedding-model
internals · (3) tracing cache decisions at the same level as routing, justified by
error-cost asymmetry · (4) memoizing `EmbeddingModel` decorator rather than a
vector-accepting cache port, preserving ADR 0005 (D1).

**Compliance note**: a short section on what is logged, what is replayable, and
why traceability is architectural rather than bolted on. The AI Act transparency
angle (art. 50) should be **stated with a dated source link**, not asserted from
memory, and must not claim certified compliance — gatewAI is an infrastructure
component. The existing CSRD register sets the tone: useful, sourced, no
overpromise.

---

## Expected tests

- **Unit**: justification per strategy, conformal quantile, margin, occlusion on
  simulated embeddings, `RoutingConfig` hash stability (same config → same hash,
  order-insensitive where it should be)
- **Property**: empirical conformal coverage on synthetic data
- **Integration**: Testcontainers Postgres + pgvector, full cycle request →
  persisted decisions → replay via the API (new capability, D7)
- **Hot configuration**: `PUT /v1/admin/routing` changes the version and
  invalidates the running calibration
- **Degradation**: DB down, calibration stale, embedding down — routing continues
  in all three
- **Latency non-regression**: the nominal path must *improve* thanks to 0.2, not
  degrade
- **ArchUnit**: unchanged and still green — the new domain types must not drag in
  Spring, JPA or Spring AI

---

## Order of implementation

```
Batch 0   Prerequisites (Flyway, shared embedding, correlation id)   ✅ done
Batch 1   Uniform explanation contract                            ✅ done
Batch 2   Routing + cache decision persistence           ✅ done
Batch 5   Evaluation                                                ✅ done
Batch 3   Conformal calibration (cache first)                       ✅ done
Batch 4   Calibrated cascade routing (+ client pinning)          ✅ done
Batch 6   Observability                                          ✅ done
Batch 7   Occlusion attribution                                  ✅ done
Batch 8   Counterfactuals
Batch 9   API and dashboard
Batch 10  Documentation                  ← continuous
```

Each batch ships independently, with tests and documentation. No long-lived
branch. `./mvnw test` green before every commit.

---

## Open decisions (to settle before batch 0)

1. **Client pinning (D3)** — ✅ **settled in batch 4**: implemented. A
   registered model id is honoured and traced as `CLIENT_PINNED`; an
   unregistered one is still routed; `client-pinning=false` makes routing
   mandatory.
2. **Opt-in plaintext prompt storage** — needed for true replay of an
   `explain` on a past decision (a hash cannot be re-embedded). Include in v2
   behind a default-off flag with retention, or defer to the guardrails/PII work?
   Recommendation: include the flag, default off.
3. **Tracing dependency (D2)** — plain correlation id only (recommended), or add
   `micrometer-tracing` + OTel and get real spans?
4. **Evaluation in CI (5.3)** — ✅ **settled in batch 5**: recorded vector
   fixtures, committed with their provenance. `./mvnw test` stays hermetic and
   the live run is a manual, explicitly flagged recording step.

---

## Definition of done

- [x] All three classifiers produce a usable justification
- [x] Cache and routing decisions persisted and versioned (replay API: batch 9)
- [x] Cache and routing thresholds calibrated, with a tested fallback to fixed values
- [x] Cascade routing implemented, its gates calibrated
- [x] Six quality metrics published and tracked in CI — all six measured and
      baselined since batch 4
- [x] The request embedding is computed once per request
- [ ] The dashboard exposes "why this decision"
- [x] Versioned migrations in place
- [x] `limitations.md` covers the new methodological limits
- [ ] No latency regression on the nominal path
- [ ] ArchUnit, Checkstyle, SpotBugs and the native profile still green
