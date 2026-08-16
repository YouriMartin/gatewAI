# v3 — In-process embedding and multi-instance readiness

Two lots, sequential. Lot A removes the embedding model's network hop; lot B removes
the single-instance assumptions. B depends on A only in that A changes the embedding
dimensions and every calibration/fixture with them — doing B first would mean
redoing part of its test surface.

Same conventions as v2: each batch is independently mergeable, has explicit
acceptance criteria, and updates the docs it invalidates in the same commit.

---

## Scope boundary to state up front

**Lot A does not remove Ollama from the product.** It removes it from the *decision
path* — cache lookup, cache store, semantic routes, occlusion attribution,
counterfactuals. Ollama remains the default **egress** (`local-small` / `local-medium`
/ `local-large` in the registry are three Qwen sizes), so `docker-compose.yml`
still ships it.

What changes is the dependency shape:

| | Before | After |
|---|---|---|
| Cache + routing work without Ollama | no | **yes** |
| `./mvnw spring-boot:run` needs | Postgres + Ollama | **Postgres** |
| `docker compose -f compose.yaml` (dev infra) | pgvector + Ollama | **pgvector** |
| `docker-compose.yml` (plug & play, local inference) | pgvector + Ollama | pgvector + Ollama |
| First-start download | ~3 GB | 0 (dev) / ~3 GB (plug & play) |

The pitch line this unlocks is "one jar, one Postgres, no API key, starts in
seconds" for the gateway itself; local inference stays an opt-in extra container.

---

# Lot A — In-process ONNX embedding

**Goal.** Replace the Ollama-hosted `nomic-embed-text` (768 dim, HTTP) with an
ONNX sentence transformer running in the JVM (384 dim, in-process), without
touching the cache advisor, the classifiers, or the explanation services.

**Why it is low risk.** `TransformersEmbeddingModel` (Spring AI 2.0,
`spring-ai-starter-model-transformers`, DJL + ONNX Runtime) implements
`EmbeddingModel`. `MemoizingEmbeddingModel` decorates an `EmbeddingModel`.
`SpringAiTextEmbedder` depends on `EmbeddingModel`. The swap is a bean, and
ADR 0007 holds unchanged.

**Non-goal.** No classification head. The router keeps deciding by cosine
similarity against route examples, because the conformal calibration, the
occlusion attribution and the counterfactuals are all decompositions of that
similarity. A sequence-classification model would return a label and delete
three features. Contrastive fine-tuning of the embedding stays open (post-v3)
precisely because it preserves them.

## A.1 — Bean swap, model bundled — ✅ done

- Add `spring-ai-starter-model-transformers` to `pom.xml`.
- In `infrastructure/llm/EmbeddingConfiguration`, build a
  `TransformersEmbeddingModel` from `classpath:` resources instead of the Ollama
  embedding model. Keep `MemoizingEmbeddingModel` as the `@Primary` decorator —
  it must still be the bean everything else is injected with.
- Ship the ONNX model and `tokenizer.json` as classpath resources under
  `src/main/resources/onnx/<model>/`. Prefer the **int8-quantised** export
  (~20–30 MB) over fp32 (~90 MB); record which one in the ADR.
- Remove `spring.ai.ollama.embedding.*` and
  `spring.ai.ollama.init.embedding.additional-models` from
  `application.properties`. Keep everything under `gatewai.providers.ollama`
  (that is egress, not embedding).
- Set `spring.ai.embedding.transformer.tokenizer.options.padding=true` if ragged
  array errors appear.

**Acceptance**
- The context starts with no Ollama reachable, and `POST /v1/chat/completions`
  against the `mock` profile completes with cache + routing running.
- `compose.yaml` no longer declares an `ollama` service.
- One uncached request still triggers exactly **one** embedding computation
  (ADR 0007 invariant) — assert on the memo, not on HTTP calls, since there are
  none now.
- Jar size is recorded in the commit message.

## A.2 — Dimensions and vector schema — ✅ done

- `spring.ai.vectorstore.pgvector.dimensions=768` → `384`.
- The `vector_store` table is Spring AI-managed, **not** Flyway-managed
  (ADR 0005). A dimension change means the existing table must be dropped and
  recreated. Write an explicit upgrade note in `docs/technical/data-model.md`:
  the cache is a cache, it is dropped and refills; nothing else is lost.
- Verify the HNSW + cosine index is rebuilt at the new dimension.
- `docker/postgres/init.sql` unchanged (extension only).

**Acceptance** — both met, on real databases:
- Fresh stack (image built from `docker-compose.yml`; started as pgvector +
  gateway, `mock` profile, Ollama skipped — see
  [`../decisions.md`](../decisions.md)): `vector_store.embedding` is
  `vector(384)`, `spring_ai_vector_index` is `hnsw (embedding
  vector_cosine_ops)`, and two identical requests give MISS then HIT. Cold start
  8.0 s; the jar→`/tmp/gatewai-onnx` model copy is 130 MB, once.
- Upgrade path verified on a database holding a 768-dim row: booting at 384
  **starts fine and kills the cache silently** (`different vector dimensions 768
  and 384` per lookup, traced `outcome = ERROR`, nothing stored);
  `DROP TABLE vector_store` + restart recreates it at 384 with the HNSW cosine
  index and the cache works, with `request_log`, the decision tables and
  `conformal_calibration` untouched. Written up in
  [`../technical/data-model.md`](../technical/data-model.md).
- `spring.ai.vectorstore.pgvector.schema-validation=true` was measured as a
  fail-fast guard: it names both widths on an existing table, and refuses to
  start on a fresh one. Ships commented, not enabled.

## A.3 — Model selection, measured — ✅ done

Do not default to `all-MiniLM-L6-v2`. It is English-centric, and batch 5 measured
that **English was already the weak side** (45.1 % vs 79.6 % FR at the fixed
threshold). Evaluate at least:

| Candidate | Dim | Note |
|---|---|---|
| `sentence-transformers/all-MiniLM-L6-v2` | 384 | baseline, EN-only |
| `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` | 384 | EN/FR |
| `intfloat/multilingual-e5-small` | 384 | EN/FR, needs `query:`/`passage:` prefixes — **check this against ADR 0007**, which assumes no asymmetric prefixes |

Export with `optimum-cli`. Score each with the harness (A.4) on both the fixed
and the calibrated threshold, per language, and pick on the numbers.

**Acceptance** — met, all three scored on the harness:
- [`../technical/evaluation.md`](../technical/evaluation.md) carries the
  comparison (routing fixed/calibrated, EN, FR, hand-overs, mean margin, cache
  FP/FN/hit at both thresholds, decision latency p50/p95).
- **`paraphrase-multilingual-MiniLM-L12-v2` ships**, confirmed rather than
  assumed: 82.0 % calibrated routing against 73.0 % (`all-MiniLM-L6-v2`, which
  drops to 63.3 % on French) and 81.0 % (`multilingual-e5-small`). e5 wins at the
  *fixed* threshold and loses on what the rest of the system needs: 76.8 % cache
  false positives at the shipped 0.92, and a mean routing margin of 0.023 — the
  cascade band itself — which escalates 56 % of requests.
- ADR 0007 needs no amendment: the e5-style model did not win, and it was scored
  **without** prefixes because that is how the gateway would run it.
- Deviation: the candidates came from the published `Xenova/*` int8 ONNX exports
  rather than a local `optimum-cli` run — see [`../decisions.md`](../decisions.md).

**Handed to A.4** (both change `routing_config_version`, so they belong before
the re-record, not after):
- `route-similarity-threshold=0.60` is `nomic-embed-text`'s scale — on the new
  model it hands **88 of 100** prompts to the heuristic. Calibrated: **0.2221**.
- α = 0.10 now buys 14.3 % cache false positives at **88.6 %** false negatives
  (13 % hit rate), against 12.5 % / 65.9 % on the old model.

## A.4 — Fixtures, baselines, calibration

The harness will fail the build on its own — that is the design working. Do the
re-record deliberately, not reactively.

- Re-record with the new model:
  `./mvnw -Pit test -Dtest=EvalFixtureRecorderTest -Deval.record=true`
  (no longer needs `docker compose up -d ollama` — note this in
  `testing-and-quality.md`).
- Fixture provenance (`embeddingModel`, `dimensions`, dataset digest,
  `RoutingConfigVersion`) must reflect the new model.
- Update `src/test/resources/eval/baselines.json` to the new measured run.
  **Raising is routine; any lowering gets a justification line in the commit
  message.**
- Re-fit both calibrations (`POST /v1/admin/calibration`) and record the new
  `q̂` values in `conformal-calibration.md`. The old ones describe a system that
  no longer exists — which is exactly what the `embedding_model` provenance
  column and `gatewai_conformal_calibration_stale` are there to make loud.

**Acceptance**
- `./mvnw test` green, harness report regenerated.
- The staleness path was observed in practice: on first start after the swap,
  `GET /v1/admin/calibration` returns `STALE` for both targets and the gauge
  reads 1, before recalibration.
- The invariant "embedding strategy still beats the heuristic it falls back to"
  still holds.

## A.5 — Native image, docs, ADR

- ONNX Runtime loads a native library. Add the reflection/resource hints and
  extend `NativeRuntimeHintsTest`. Native status stays "native-ready, not
  validated" — do not claim otherwise.
- New **ADR 0011 — In-process ONNX embedding instead of a model server**
  (0008–0010 were taken by v2 batch 10): context (three HTTP round trips became
  one after ADR 0007, but the hop remained; a decision-path dependency on a
  separate container contradicts the single-jar promise), decision, consequences
  (dimension change, calibration invalidation, native-image cost, jar size).
- Update: `tech-stack.md`, `semantic-cache.md`, `routing.md`,
  `evaluation.md`, `conformal-calibration.md`, `testing-and-quality.md`,
  `data-model.md`, `build-and-packaging.md`, README prerequisites.
- **Fix the three known doc drifts while in there**: `routing.md`'s default
  registry table still lists Anthropic models; ADR 0003 still says "defaulting
  to Anthropic Claude"; ADR 0002 still says streaming short-circuit is deferred.

**Acceptance**
- No doc still tells a reader that Ollama is required for the cache or the
  router.
- Decision latency p50/p95 measured at fixture time and published.

---

# Lot B — Multi-instance readiness

**Goal.** Two or more gateway replicas behind a load balancer, sharing one
Postgres, with no request-affinity requirement and no silently divergent
behaviour between nodes.

**Constraint.** No Redis. Everything goes through the Postgres already required.
The single-infra-container story is the product argument; adding a second one to
solve locking would spend it.

## B.0 — Audit and pin the contract

Before writing anything, produce `docs/technical/clustering.md` listing every
piece of node-local state and its verdict. Expected inventory:

| State | Today | Verdict |
|---|---|---|
| `RateLimiter` buckets | `ConcurrentHashMap` | **must be shared** (B.3) |
| `InMemoryDeferredJobStore` | heap | **must be persisted** (B.2) |
| Live `RoutingConfig` | in-memory via `ClassifierRoutingConfigAdapter` | **must be persisted + propagated** (B.1) |
| Semantic route index | rebuilt in memory per node | derived from config — fine once B.1 lands |
| Conformal snapshot | refreshed ≤ 1/min per node | fine; document the ≤ 60 s propagation delay |
| `InMemoryAttributionCache` (LRU 500) | heap | fine — a cache, keyed on prompt hash + embedding model + config version |
| `RequestEmbeddingMemo` | per-request scoped value | fine by construction |
| `AdminSeedRunner` | idempotent by key hash | needs a concurrent-startup test (B.4) |
| Scheduled workers | run on every node | **must be leader-gated** (B.4) |

This table is the acceptance criterion for the whole lot: at the end, every row
says "fine" and says why.

## B.1 — Persist and propagate the routing config

The highest-risk item and the easiest to overlook. Today a
`PUT /v1/admin/routing` on node A leaves node B routing on the old rules
indefinitely — and both keep stamping their own `routing_config_version` onto
decisions, which makes the trace lie.

- New table `routing_config` (single row, or one row per version with a
  `current` flag), Flyway `V6__routing_config.sql`.
- `RoutingConfigPort` gains a persistent implementation; the in-memory adapter
  becomes a per-node cache over it.
- Propagation: poll on a short interval (5–10 s) **or** Postgres
  `LISTEN`/`NOTIFY`. Prefer polling first — fewer moving parts, and the
  divergence window is bounded and documentable. NOTIFY can come later.
- `RoutingConfigVersionTracker` must log the change on every node that picks it
  up, and `gatewai_routing_config_changes_total` must increment once per node
  per change (document that, so the drift panel is read correctly with N
  replicas).
- Route index rebuild happens on version change, per node.

**Acceptance**
- Two instances, a `PUT` on one, and the other routes on the new rules within
  the documented window.
- A restart of either node loads the persisted config, not
  `application.properties` defaults.
- `routing_config_version` is identical across nodes once converged.

## B.2 — Persist deferred jobs

- Replace `InMemoryDeferredJobStore` with a JPA-backed `DeferredJobStore`
  (`V7__deferred_job.sql`): id, status, request payload, chosen zone, result,
  error, timestamps.
- Claim with `SELECT ... FOR UPDATE SKIP LOCKED` so N workers never run the same
  job.
- Recover `RUNNING` jobs orphaned by a crash: a lease timestamp plus a
  reclaim rule, or requeue on startup. Pick one and state it.
- `CarbonZoneContext` binding is unchanged.

**Acceptance**
- A job submitted on node A completes on node B and is readable from either.
- Jobs survive a restart.
- Two workers polling concurrently execute each job exactly once (test with a
  deliberate concurrent claim).

## B.3 — Distributed rate limiting, without Redis

- Move Bucket4j to a Postgres-backed store (`bucket4j-postgresql`, advisory-lock
  or `SELECT FOR UPDATE` strategy). Keep the same
  `gatewai.ratelimit.{enabled,requests-per-minute}` surface.
- Keep the in-memory implementation available behind a property for
  single-node deployments — it is faster and correct there.
- Watch the added latency on `POST /v1/chat/completions`: this is now a database
  round trip on the hot path. Measure it and publish the number; if it is
  material, consider a local-token-batch strategy before reaching for Redis.

**Acceptance**
- The 60 req/min limit holds across two instances, not per instance.
- The single-node mode still passes the existing rate-limit tests unchanged.
- Added p95 latency measured and recorded.

## B.4 — Leader-gated scheduled work

`CarbonAwareDispatchWorker`, `DecisionPurgeWorker` and the calibration snapshot
refresh currently run on every node. Purging twice is harmless; dispatching
twice is not (B.2 fixes that specific case, but the pattern needs a rule).

- Introduce a small `LeaderLock` abstraction over `pg_try_advisory_lock`, one
  lock key per job. Non-blocking: a node that does not get the lock skips the
  tick.
- Apply to the purge worker and any future periodic job. The dispatch worker can
  either be leader-gated or rely on `SKIP LOCKED`; choose and document.
- The calibration snapshot refresh stays per-node (it is a read).

**Acceptance**
- With two instances, the purge runs once per interval, not twice.
- Losing the leader node causes another to pick the work up on the next tick,
  with no manual intervention.
- Concurrent cold start of two instances with the same `GATEWAI_ADMIN_API_KEY`
  seeds exactly one admin client (unique constraint on `api_key_hash` must be
  caught, not thrown to the startup).

## B.5 — Prove it, then say it

- Add a compose file with **two gateway replicas** behind a minimal load
  balancer, sharing one Postgres.
- Add an integration test (or a documented manual scenario) exercising:
  config propagation, cross-node job completion, shared rate limit, single
  purge.
- Add `application=gatewai` alongside an `instance` tag on metrics so dashboards
  can distinguish nodes; check the drift panel still reads correctly when
  summed across replicas.
- Rewrite the **"Single-instance assumptions (not cluster-ready)"** section of
  `functional/limitations.md`. It should state what is now supported, and what
  is still node-local and why that is acceptable (attribution LRU, conformal
  snapshot delay, in-memory route index).

**Acceptance**
- `limitations.md` no longer says "running multiple replicas is not supported
  as-is".
- Every row of the B.0 table reads "fine", with a reason.

---

## Suggested order and rough shape

| Batch | Depends on | Risk |
|---|---|---|
| A.1 | — | low |
| A.2 | A.1 | low |
| A.3 | A.2, A.4 harness runnable | low, mostly measurement |
| A.4 | A.3 | low, mechanical |
| A.5 | A.1–A.4 | low |
| B.0 | A | none (writing) |
| B.1 | B.0 | **high** — hot config touches routing, tracing and calibration invalidation |
| B.2 | B.0 | medium |
| B.3 | B.0 | medium — hot-path latency |
| B.4 | B.2 | low |
| B.5 | B.1–B.4 | low |

Merge A entirely before starting B. A changes the numbers every baseline and
calibration is written against; B changes where state lives. Interleaving them
would make a regression ambiguous between the two, which is the one thing the
evaluation harness cannot help with.

## Rules for the implementation sessions

Carried over from the v2 conventions, restated because they are what makes this
reviewable in short evening sessions:

- One batch per branch, one batch per PR.
- `./mvnw verify` green before opening it — Checkstyle, tests, SpotBugs.
- ArchUnit rules are not negotiable: a new adapter package gets declared in
  `ArchitectureTest` or the build fails, and that is correct.
- Docs invalidated by a change are updated **in the same commit**, not later.
- Any decision taken during implementation that deviates from this document goes
  into `docs/decisions.md` with its reason, so review reads the deviation rather
  than rediscovering it.
- Numbers are measured, never estimated. If a batch claims a latency or an
  accuracy, the harness or a benchmark produced it.
