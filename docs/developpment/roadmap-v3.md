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

## A.4 — Fixtures, baselines, calibration — ✅ done

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

**Acceptance** — all three met:
- `./mvnw -DskipFrontend verify` **green: 556 tests, 0 failures** (Checkstyle +
  SpotBugs included), report regenerated. First green build since A.1.
- The staleness path was observed on a real database: a v2-era calibration row
  (`embedding_model = nomic-embed-text`) makes the new build log
  `CACHE/ROUTING calibration is STALE — falling back to the fixed threshold`,
  `GET /v1/admin/calibration` return `STALE` for both targets with
  `applied=false`, and `gatewai_conformal_calibration_stale{target=…}` read
  **1.0**. After `POST /v1/admin/calibration`: both `VALID`, gauge **0.0**,
  `gatewai_conformal_threshold` publishing 0.9526 and 0.2221.
- The live fit matches the hermetic harness to four decimals (cache q̂ 0.952568
  vs 0.9526; routing 0.777884 → threshold 0.222116 vs 0.2221), which is the two
  paths agreeing rather than one being trusted.
- **Embedding still beats the heuristic it falls back to**: 81 % against 34 % on
  the same test set.
- Found and fixed while doing it: `SpringAiTextEmbedder` stamped `"unknown"` as
  the calibration's embedding model (it read a property A.1 deleted), which would
  have made every future calibration look current forever. See
  [`../decisions.md`](../decisions.md).

## A.5 — Native image, docs, ADR — ✅ done

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

**Acceptance** — met:
- Hints for all three invisible pieces (`onnx/**`, `ai/onnxruntime/native/**`,
  `native/lib/**`) plus the `ai.onnxruntime` types JNI instantiates, in
  `EmbeddingNativeRuntimeHints`, with `EmbeddingNativeRuntimeHintsTest` reading
  the model path **from the shipped properties** so a renamed directory fails
  here rather than in a native build nobody runs daily. Native status unchanged:
  **native-ready, not validated**.
- [ADR 0011](../technical/adr/0011-in-process-onnx-embedding.md) written, with
  the alternatives that were real (keep Ollama / hosted embedding API) and the
  consequences measured rather than guessed.
- The three known drifts are fixed: `routing.md`'s registry table now shows the
  local-first Qwen defaults, ADR 0003 no longer "defaults to Anthropic Claude",
  ADR 0002 no longer calls the streaming short-circuit deferred.
- No doc claims Ollama is needed to cache or route. The sweep also caught
  `overview.md`, the vLLM comparison, `observability.md`, `README.md`, and a dead
  `SPRING_AI_OLLAMA_BASE_URL` still exported by CI, `dev.sh` and the plug & play
  compose for an auto-config that no longer exists.
- Decision latency published: **p50 3.2 ms / p95 8.2 ms**, recorded live at
  fixture time (34 / 44 ms before lot A).
- Deviation: the hints live in `infrastructure/llm` with their own test rather
  than extending the web-package `NativeRuntimeHintsTest` — see
  [`../decisions.md`](../decisions.md).

---

## Lot A — done

| Batch | Outcome |
|---|---|
| A.1 | Bean swap; jar 161 → 349 MiB; boots in 7.9 s with no model server |
| A.2 | 384-dim schema; upgrade = `DROP TABLE vector_store`, verified both ways |
| A.3 | `paraphrase-multilingual-MiniLM-L12-v2` chosen on measurements |
| A.4 | Fixtures re-recorded, baselines re-set, calibrations re-fit; build green |
| A.5 | Native hints, ADR 0011, doc drifts |

What lot A actually bought: the **decision path** (cache, routing, attribution,
counterfactuals) needs **Postgres alone**, decisions got ~10× faster (34 → 3.2 ms
p50), and calibrated routing accuracy moved 83.0 % → 82.0 %. What it cost: a
349 MiB jar, a build-time model download (pinned by checksum, cached in
`~/.m2`), and a mandatory
`DROP TABLE vector_store` on upgrade.

---

# Lot B — Multi-instance readiness

**Goal.** Two or more gateway replicas behind a load balancer, sharing one
Postgres, with no request-affinity requirement and no silently divergent
behaviour between nodes.

**Constraint.** No Redis. Everything goes through the Postgres already required.
The single-infra-container story is the product argument; adding a second one to
solve locking would spend it.

## B.0 — Audit and pin the contract — ✅ done

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

**Written** as [`../technical/clustering.md`](../technical/clustering.md), with
every row's verdict, the batch that owns it, and a per-batch status table. It
shipped with B.1 rather than as its own commit — see
[`../decisions.md`](../decisions.md).

## B.1 — Persist and propagate the routing config — ✅ done

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

**Acceptance** — all three met, measured on two JVMs sharing one pgvector
container (packaged jar, `mock` profile), written up in
[`../technical/clustering.md`](../technical/clustering.md):
- A `PUT` on node A reached node B in **2.05 s** at the default 5 s interval —
  rules *and* cascade band. Node A applied revisions 2 then 3 (its own two
  column-scoped writes); node B adopted 3 directly, which is intermediate
  revisions coalescing rather than a missed update.
- Node B restarted at **revision 3**, not at the `application.properties`
  defaults, with no `PUT` replayed.
- `routing_config_version` identical (`ce59c5bc8f5961ff`) on the decisions each
  node recorded.
- `gatewai_routing_config_changes_total` read **1.0 on each node** after one
  edit — the N-replicas reading is documented in `observability.md` beside the
  drift panel, including the case of a node that served no traffic between two
  edits and therefore counts nothing.
- Two behaviours verified in SQL rather than in Java, because that is where they
  live: the losing `INSERT … ON CONFLICT (id) DO NOTHING` reports `INSERT 0 0`
  and leaves the winner's row intact, and `routing_config_single_row` refuses an
  `id = 2`.
- Resilience checked by stopping Postgres under a running node: one failed poll
  logged `WARN … keeping revision 5`, the node kept its rules, and it accepted
  the next `PUT` at revision 6 after recovery.
- `./mvnw -DskipFrontend verify` green: **580 tests**, 0 failures, Checkstyle and
  SpotBugs included.

**Deviations** (detail in [`../decisions.md`](../decisions.md)): the two writers
are **column-scoped** (`saveConfig` / `saveCascadeMarginBand`) instead of one
whole-row write, and the seed is an `ON CONFLICT DO NOTHING` insert instead of a
caught constraint violation.

## B.2 — Persist deferred jobs — ✅ done

- Replace `InMemoryDeferredJobStore` with a JPA-backed `DeferredJobStore`
  (`V7__deferred_job.sql`): id, status, request payload, chosen zone, result,
  error, timestamps.
- Claim with `SELECT ... FOR UPDATE SKIP LOCKED` so N workers never run the same
  job.
- Recover `RUNNING` jobs orphaned by a crash: a lease timestamp plus a
  reclaim rule, or requeue on startup. Pick one and state it.
- `CarbonZoneContext` binding is unchanged.

**Chosen recovery rule**: a **lease** (`claimed_by`, `lease_expires_at`), swept at
the start of every dispatch tick. Requeue-on-startup was rejected because it
depends on the dead node coming back; a lease lets whichever node is alive recover
the work. Stated consequence: concurrent claims are **exactly-once**, a lease
expiry is **at-least-once** — a job that genuinely outlives
`gatewai.dispatch.job-lease-ms` (5 min) can run twice.

**Acceptance** — all three met:
- Verified on two JVMs sharing one Postgres: 2 jobs submitted on node A survived
  **both nodes being stopped**, were completed by node B afterwards
  (`claimed_by = node-b`), and the full result was readable from the *restarted*
  node A as well as from B.
- Exactly-once under real concurrency: 30 further jobs with both nodes
  dispatching gave **32 jobs, 32 executions, 0 duplicates**, counted in
  `request_log` by `correlation_id` (the job id) — an execution count, not an
  inspection of the queue's own state. Claims split 10 / 22 across the nodes.
- The deliberate concurrent claim is an automated test:
  `JpaDeferredJobStoreClaimTest` (`@Tag("integration")`) runs two threads claiming
  40 jobs against a real Postgres and asserts none lost, none claimed twice; a
  second test strands a claim and shows the lease sweep returning it to the queue
  with its zone cleared.
- `./mvnw -DskipFrontend verify` green: **592 tests**, 0 failures.

**Deviations** (detail in [`../decisions.md`](../decisions.md)): the port lost
`findQueued()` instead of gaining a claim next to it; claims are **one job at a
time**, not a batch; the JPA store lives in `infrastructure/persistence` rather
than `infrastructure/dispatch`; and B.4's open question about the dispatch worker
is answered here — **no leader gate**, the claim is the coordination.

**Named gap, not fixed here**: `deferred_job` persists prompts in clear text and
has no retention policy. Documented in `carbon-aware-dispatch.md`,
`decision-tracing.md`'s compliance note and `limitations.md`.

## B.3 — Distributed rate limiting, without Redis — ✅ done

- Move Bucket4j to a Postgres-backed store (`bucket4j-postgresql`, advisory-lock
  or `SELECT FOR UPDATE` strategy). Keep the same
  `gatewai.ratelimit.{enabled,requests-per-minute}` surface.
- Keep the in-memory implementation available behind a property for
  single-node deployments — it is faster and correct there.
- Watch the added latency on `POST /v1/chat/completions`: this is now a database
  round trip on the hot path. Measure it and publish the number; if it is
  material, consider a local-token-batch strategy before reaching for Redis.

**Strategy chosen**: `SELECT … FOR UPDATE` with `PrimaryKeyMapper.STRING`, over the
advisory-lock variant, which keys on a `bigint` and would mean hashing the client id
to 64 bits — a collision there silently merges two tenants' quotas. Table
`rate_limit_bucket` (`V8`), one opaque Bucket4j state per client.

**Acceptance** — all three met:
- The limit holds across two instances, and the *before* was measured too: two
  nodes with the limit at 6/min let **10 of 10** requests through on the in-memory
  store, and exactly **6 allowed + 4 × `429`** (`Retry-After: 9`) on the shared one.
  Also asserted without HTTP by `PostgresRateLimiterTest` (`@Tag("integration")`),
  where two limiter instances over one DataSource stand in for two replicas.
- The single-node path kept **every assertion of its existing test verbatim**; only
  the constructor name changed (`RateLimiter` → `InMemoryRateLimiter`), the
  interface having taken the old name.
- Latency published: the check costs **3.4 ms p50 / 3.8 ms p95** against
  **21–24 µs** in the heap, read from the app's own
  `gatewai_ratelimit_check_seconds` (0.5/0.95 quantiles enabled by default).
  End-to-end on a `mock`-egress cache hit that is +4 to +7 ms on ~22 ms, which is
  consistent with the isolated figure once the ±2.6 ms run-to-run variance
  (measured, two identical in-memory runs) is accounted for — not a hidden extra
  cost.
- **The token-batching optimisation was therefore not built.** At 3.8 ms it is
  under 1 % of a real model call; the roadmap said "if it is material", and it is
  not. The metric stays so that claim can be re-checked instead of trusted.
- `./mvnw -DskipFrontend verify` green: **593 tests**.

**Deviation**: the default stays `memory`, not `postgres`. Correct and free on the
single node most self-hosted deployments run, one property away for a cluster — but
it does mean a cluster that forgets the property silently grants N × the quota, and
that is now the one footgun lot B leaves standing. Stated in `security.md`,
`clustering.md`, `limitations.md` and the property's own comment; B.5's two-replica
compose sets it.

## B.4 — Leader-gated scheduled work — ✅ done

`CarbonAwareDispatchWorker`, `DecisionPurgeWorker` and the calibration snapshot
refresh currently run on every node. Purging twice is harmless; dispatching
twice is not (B.2 fixes that specific case, but the pattern needs a rule).

- Introduce a small `LeaderLock` abstraction over `pg_try_advisory_lock`, one
  lock key per job. Non-blocking: a node that does not get the lock skips the
  tick.
- Apply to the purge worker and any future periodic job. ~~The dispatch worker can
  either be leader-gated or rely on `SKIP LOCKED`; choose and document.~~
  **Answered by B.2: no gate on the dispatch worker.** A leader lock would make one
  node do all the dispatching and the others spectate, which defeats a shared
  queue; the `SKIP LOCKED` claim is the coordination. Gating is for periodic jobs
  that are not idempotent and have no claim of their own — the purge.
- The calibration snapshot refresh stays per-node (it is a read).

**Acceptance** — all three met, on two JVMs sharing one Postgres:
- The purge gate was tested by **holding the lock from a `psql` session**, because
  the obvious experiment proves nothing: a second node purging after the first
  finds nothing to delete either way, so "one log line" is not evidence. With the
  lock held externally, both nodes logged `skipping` on every tick (7 and 6 skips
  over 20 s at a 3 s interval) and the purgeable rows stayed. That the SQL session
  and the Java code contended at all is also the proof that
  `pg_try_advisory_xact_lock(-189118924, 1)` is computed identically on both sides.
- Losing a node needs no intervention: node B killed, node A purged on its next
  tick. Note there is no leader to lose — over the run both nodes purged at
  different times, which is the design rather than a wobble.
- Concurrent cold start: two nodes started together with the same
  `GATEWAI_ADMIN_API_KEY` against an empty `api_client` produced **exactly one**
  admin, both up, both accepting the key. The gate itself was then proven
  deterministically the same way as the purge — a node booting while `ADMIN_SEED`
  was held logged *"Another instance is seeding the admin client; skipping"*, left
  the table empty and **started anyway**; terminating the holder's backend released
  the lock (`pg_locks` empty) and the next start seeded exactly one.
- The unique-constraint catch is in, and it sits at the **transaction boundary**,
  not around the insert: the insert joins the lock's transaction, so a violation
  surfaces at commit, one frame further out. A catch around `save()` would have
  looked right and never fired.
- `./mvnw -DskipFrontend verify` green: **599 tests**. `AdvisoryLeaderLockTest`
  (`@Tag("integration")`) covers mutual exclusion, release on success and on
  failure, and that two tasks do not block each other, against a real database.

**Deviations** (detail in [`../decisions.md`](../decisions.md)): the lock is
transaction-scoped rather than session-scoped, lock ids are declared in a
`LeaderTask` enum rather than hashed from a name, and the admin seeding is gated
too — the plan only asked for a test there, but the lock is what makes the
*random-key* mode produce one admin instead of two.

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
