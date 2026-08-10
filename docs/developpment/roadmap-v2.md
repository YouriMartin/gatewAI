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
| D3 | `CLIENT_PINNED` traces an existing behaviour | `RoutingAdvisor.adviseCall` classifies **unconditionally** on any non-blank user text and always rewrites the model id (`RoutingAdvisor.java:44-68`). A client cannot pin a model today; `routing.md` overstates this | Either implement real pinning (recommended, see 4.2) or drop the enum value. Tracing a reason that can never occur is worse than not having it |
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

## Batch 0 — Prerequisites (blocking)

### 0.1 Versioned migrations **[post-v1]**

Move from `ddl-auto=update` (`application.properties:61`) to **Flyway**, with a
baseline migration for the existing schema (`request_log`, `api_client`).

Watch out: **`PgVectorStore` initializes its own `vector_store` table.** Decide
explicitly whether the baseline owns that table (then
`spring.ai.vectorstore.pgvector.initialize-schema=false`) or whether Spring AI
keeps owning it and Flyway ignores it. Do not let both manage it.

Once Flyway exists, the persistent deferred-job store becomes cheap **[post-v1]** —
noted here, not scheduled.

### 0.2 Shared request embedding **[post-v1]**

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

### 0.3 Correlation id (new — see D2)

Generate a correlation id at ingress (honouring an inbound `X-Request-Id` /
`traceparent` when present), bind it into `RequestContext.traceId`, propagate it
through the deferred path (`DeferredChatService` already re-binds the context),
and **add `correlationId` to `RequestLog` + `RequestLogEntity`** so carbon,
cache and routing records join on one key (D6). Return it as a response header.

Adding `micrometer-tracing` + OTel is a **separate, optional** decision — the
correlation id above is enough for everything v2 needs.

---

## Batch 1 — Uniform explanation contract

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

### Acceptance

All three strategies produce a non-empty justification; the fallback case is
distinguishable from the nominal one; unit tests per strategy.

---

## Batch 2 — Decision persistence

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

### Acceptance

Every request produces a `CacheDecision`, and a `RoutingDecision` except on a
cache hit · a hot route edit changes `routing_config_version` without a restart ·
DB down → nominal service preserved, tested.

---

## Batch 5 — Evaluation (before batch 3, deliberately)

Calibrating without an evaluation set replaces a guessed threshold with a
quantile computed on nothing.

### 5.1 Datasets

Versioned in the repo, **calibration and test kept disjoint**:

- **Routing**: `(prompt, expected_tier)`
- **Cache**: `(query, entry, judgment)`

Include adversarial cases: ambiguous prompts, out-of-distribution prompts, and
**bilingual FR/EN** — `routing.md` already flags `nomic-embed-text` as
English-centric, which makes this a known risk to *measure* rather than assume.

Target n ≥ 200 per calibration target (batch 3). Labelling is manual and is the
real cost of v2; budget it as such.

### 5.2 Metrics

| Metric | Definition |
|---|---|
| Routing accuracy | % of tiers matching the label |
| Cache accuracy | false-positive / false-negative rate |
| Estimated savings | € and gCO2 vs an all-premium baseline, via the existing accounting |
| Escalation rate | % reaching cascade level 3 |
| Conformal coverage | empirical vs target 1−α |
| Decision latency | p50 / p95, excluding the LLM call |

### 5.3 Automation

A task runnable in the existing CI producing a report comparable across commits;
an accuracy regression must be detectable automatically. Note (D7) that the
harness needs a real embedding model: either a CI Ollama service or a recorded
fixture set of vectors. Prefer **fixtures** so `./mvnw test` stays hermetic, and
keep the live run as an opt-in profile.

---

## Batch 3 — Conformal calibration

The differentiating batch. It applies **to the cache first**, then to routing.

### 3.1 Method: split conformal prediction

Calibration (offline, replayable): labelled set, n ≥ 200 per target →
non-conformity score `s_i = 1 − similarity(x_i, expected_target_i)` → quantile
`q̂ = quantile_⌈(n+1)(1−α)⌉/n({s_i})` → persist `q̂` with `alpha`, `n`,
`embedding_model_version`, `routing_config_version`, date.

Inference: `prediction_set = { target c | 1 − similarity(x, c) ≤ q̂ }`.

### 3.2 Cache first

Labelling: `(query, cache entry)` pairs judged "the cached answer correctly
answers this, yes/no".

- Empty set → miss, call the model
- Singleton → hit
- Size > 1 → **do not serve the cache**; ambiguity is a risk signal

Choose **α asymmetrically** and document why: a false negative costs one LLM
call, a false positive returns *another question's answer* to the user.

### 3.3 Routing

Labelling: `(prompt, expected_tier)`. The conformal set replaces the cascade's
arbitrary ambiguity band.

Also fix the observed quantity: the **margin `top1 − top2`** is a better
confidence signal than raw similarity — 0.82 vs 0.81 is ambiguous, 0.82 vs 0.41
is not. The default 0.60 threshold itself becomes calibratable.

### 3.4 Limits to document

The guarantee is **marginal coverage**, not a per-request guarantee, and it
assumes exchangeability between calibration and production. Both go into
[`../functional/limitations.md`](../functional/limitations.md), at the same level
of honesty as the energy coefficients already flagged as provisional.

### 3.5 Invalidation and degradation

If `embedding_model_version` or `routing_config_version` changes, the calibration
is invalid: warn at startup and on hot change, expose
`gatewai.conformal.calibration.stale`, and **fall back automatically to today's
fixed thresholds**. Graceful degradation, never an outage.

### Acceptance

Recalibration triggerable via a protected `/v1/admin/**` endpoint or a command ·
empirical coverage verified on a disjoint test set within sampling tolerance ·
degraded mode tested.

---

## Batch 4 — Calibrated cascade routing

The evolution already planned in
[`../technical/routing.md`](../technical/routing.md), with batch 3's gates.

### 4.1 The cascade

1. Deterministic signals (code fence, length) — zero cost
2. Embedding routes — one local embedding call (free after 0.2 if the cache
   already embedded)
3. LLM classifier — reached **only** when level 2's conformal set is not a
   singleton

Implemented as a `CASCADE` value of `ClassifierStrategy`, dispatched in
`DelegatingComplexityClassifier` — the seam designed for it — **reusing the three
classifiers unchanged**. Note that level 1 is a *subset* of
`HeuristicComplexityClassifier` (code fence and length, not keywords), so it
needs an explicit entry point rather than a call to `classify`.

Each level reached is recorded in `escalated_to`. The share of requests reaching
level 3 is a first-class metric: it is the cost of the cascade.

### 4.2 Client pinning (see D3)

Decide and implement, so `CLIENT_PINNED` is a real reason: when the client
requests a **registered** model id, skip classification and honour it, recording
`CLIENT_PINNED`. This matches what `routing.md` already claims and makes the
gateway usable as a plain proxy for callers who know what they want. If rejected,
remove the enum value instead.

### Acceptance

Existing strategies stay selectable and unchanged · the escalation rate is
exposed · without a valid calibration, the cascade uses the fixed band and says so.

---

## Batch 6 — Observability

Extension of the Micrometer/Prometheus stack in place — no parallel mechanism.
Names registered **dotted** (D8); the Prometheus rendering is shown for reference.

| Meter (registered) | Prometheus | Type | Tags |
|---|---|---|---|
| `gatewai.routing.decisions` | `gatewai_routing_decisions_total` | counter | `tier`, `reason`, `strategy` |
| `gatewai.routing.margin` | `gatewai_routing_margin` | summary | `tier` |
| `gatewai.cascade.escalations` | `gatewai_cascade_escalations_total` | counter | `to_level` |
| `gatewai.cache.decisions` | `gatewai_cache_decisions_total` | counter | `outcome` |
| `gatewai.cache.similarity` | `gatewai_cache_similarity` | summary | — |
| `gatewai.conformal.set.size` | `gatewai_conformal_set_size` | summary | `target` |
| `gatewai.conformal.calibration.stale` | `gatewai_conformal_calibration_stale` | gauge | — |

`gatewai.cache.decisions{outcome}` **supersedes** the existing
`gatewai.cache.hits` / `.misses`: keep both for one release, document the
deprecation in [`../technical/observability.md`](../technical/observability.md),
and update the Grafana dashboard rather than leaving two sources of truth.

Per D9, decision metrics need a port that does not take a `RequestLog` — add a
sibling out port (e.g. `DecisionMetricsRecorder`) so the domain stays
framework-free.

Add to the existing Grafana dashboard a tier-distribution drift panel correlated
with `routing_config_version` changes: a mix change **without** a config change is
an input-drift signal.

OTel span attributes (`chosen_tier`, `decision_reason`, `effective_strategy`, and
a **bucketed** margin — never the raw value, cardinality) are conditional on
adopting a tracing dependency (D2). Deferred, not blocking.

---

## Batch 7 — Occlusion attribution (on demand)

1. Segment the prompt (sentences, clauses if too long)
2. `sim_full = similarity(embed(prompt), best utterance of the chosen route)`
3. For each segment *j*: `attribution_j = sim_full − similarity(embed(prompt minus j), same utterance)`
4. Normalize, sort

Cap the segment count (suggested 20, group beyond) · parallelize on the virtual
threads already in use · cache by `(prompt_hash, embedding_model_version)` ·
applicable **only** when the effective strategy is `EMBEDDING`, otherwise return
batch 1's justification as is.

Two operational notes: the per-request memo from 0.2 must not be used as the
occlusion cache (different lifetime, unbounded growth), and n+1 embedding calls
against local Ollama is the one place v2 can visibly load the box — rate-limit
the endpoint.

**Limit to document**: occlusion assumes approximate additivity of contributions,
strictly false for a contextual encoder. A useful approximation, not an exact
decomposition.

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
replay) · `docs/technical/conformal-calibration.md` (method, procedure, limits).

**Update**: `routing.md` — "Future work: cascade routing" becomes implemented ·
`semantic-cache.md` — calibrated threshold, traced decision, top-k change ·
`data-model.md` — the two new tables and the `request_log.correlation_id` column ·
`observability.md` — new meters and the cache-counter deprecation ·
`testing-and-quality.md` — Flyway, Testcontainers, the evaluation task ·
`limitations.md` — marginal (not individual) coverage, approximate additivity of
occlusion, dependence on calibration-set exchangeability · `api-reference.md` —
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
Batch 0   Prerequisites (Flyway, shared embedding, correlation id)   ← blocking
Batch 1   Uniform explanation contract
Batch 2   Routing + cache decision persistence
Batch 5   Evaluation                     ← before batch 3, which is unvalidatable without it
Batch 3   Conformal calibration (cache first)
Batch 4   Calibrated cascade routing (+ client pinning)
Batch 6   Observability
Batch 7   Occlusion attribution
Batch 8   Counterfactuals
Batch 9   API and dashboard
Batch 10  Documentation                  ← continuous
```

Each batch ships independently, with tests and documentation. No long-lived
branch. `./mvnw test` green before every commit.

---

## Open decisions (to settle before batch 0)

1. **Client pinning (D3)** — implement real pinning in 4.2, or drop
   `CLIENT_PINNED`? Recommendation: implement; the docs already promise it.
2. **Opt-in plaintext prompt storage** — needed for true replay of an
   `explain` on a past decision (a hash cannot be re-embedded). Include in v2
   behind a default-off flag with retention, or defer to the guardrails/PII work?
   Recommendation: include the flag, default off.
3. **Tracing dependency (D2)** — plain correlation id only (recommended), or add
   `micrometer-tracing` + OTel and get real spans?
4. **Evaluation in CI (5.3)** — recorded vector fixtures (recommended, keeps
   `./mvnw test` hermetic) or a live Ollama service in CI?

---

## Definition of done

- [ ] All three classifiers produce a usable justification
- [ ] Cache and routing decisions persisted, versioned, replayable
- [ ] Cache and routing thresholds calibrated, with a tested fallback to fixed values
- [ ] Cascade routing implemented, its gates calibrated
- [ ] Six quality metrics published and tracked in CI
- [ ] The request embedding is computed once per request
- [ ] The dashboard exposes "why this decision"
- [ ] Versioned migrations in place
- [ ] `limitations.md` covers the new methodological limits
- [ ] No latency regression on the nominal path
- [ ] ArchUnit, Checkstyle, SpotBugs and the native profile still green
