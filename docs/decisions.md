# Implementation deviations

Decisions taken while implementing that depart from the plan they belong to, with
the reason. Per the v3 rules, review should read the deviation here rather than
rediscover it in a diff. Newest first.

Structuring decisions still go to [`technical/adr/`](technical/adr/README.md);
this file is for the smaller "the plan said X, the code does Y" record.

## v3 lot B — B.1 (persist and propagate the routing config)

- **B.0 shipped with B.1 instead of on its own.** The plan makes B.0 a separate
  writing batch. Its output is `clustering.md`, and that file is also where B.1's
  propagation window, counter semantics and failure modes belong — writing the
  inventory first, then immediately rewriting one of its rows plus adding three
  sections, would have been two commits describing one state of the code. The
  inventory is complete and every row names the batch that owns it, so the lot's
  acceptance criterion is still checkable row by row.
- **The two writers are column-scoped, which the plan did not ask for.** The plan
  says "`RoutingConfigPort` gains a persistent implementation". The obvious shape
  is one `save(config, band)`, and it has a bug: a node's cached copy can be a
  poll interval stale, so a rules edit on node A written from that copy would
  silently revert a band edit made on node B a second earlier. `saveConfig` and
  `saveCascadeMarginBand` each touch only their own columns, under
  `SELECT … FOR UPDATE`. The cost is that one `PUT` produces two revisions, which
  is visible in the logs and harmless — the second write carries the final state.
- **The seed is `INSERT … ON CONFLICT DO NOTHING`, not a caught constraint
  violation.** The first draft was "check absent, insert, catch the violation,
  re-read". Worse in two ways: a JPA merge of an entity with an assigned id turns
  into an *update* when the row exists, so the loser of a concurrent first start
  would have overwritten the winner's configuration with its own defaults — the
  exact divergence the batch removes — and the recovery needed a second
  transaction to survive the rollback. One native statement makes losing the race
  a no-op with nothing to catch. Verified in psql: `INSERT 0 0`, winner intact.
- **The defaults are not seeded by the migration.** They live in
  `ClassifierProperties` and `application.properties`, bilingual route examples
  included; duplicating them in `V6` would have created two sources for one set
  of values with nothing keeping them equal. The first node to start writes them
  instead.
- **`@PostConstruct`, not `ApplicationRunner`, for the initial read.** Runners fire
  after the web server is accepting connections, which leaves a window where
  requests route on defaults the cluster has already replaced. Failures propagate
  and stop startup, which changes nothing operationally: `ddl-auto=validate`,
  Flyway and `AdminSeedRunner` already make this database a boot requirement.
- **`PersistentRoutingConfigPort` decorates `ClassifierRoutingConfigAdapter`
  rather than replacing it.** The plan's wording — "the in-memory adapter becomes
  a per-node cache over it" — reads as a rewrite of that class. Keeping it a
  separate `RoutingConfigPort` and marking the new one `@Primary` costs one
  annotation and buys a unit-testable write-through with no database, plus an
  obvious fallback for a future single-node mode.
- **The change counter's per-node semantics were measured, not assumed.** The plan
  asks that `gatewai_routing_config_changes_total` increment once per node per
  change. It does (1.0 on each of two nodes for one edit) — but only on a node
  that has *observed* the previous version, because the tracker recomputes on the
  request path. A node serving no traffic between two edits counts nothing. That
  qualifier is now next to the drift panel in `observability.md`.

## v3 lot A — A.5 (native image, docs, ADR)

- **The embedding hints live in `infrastructure/llm`, not in the web package.**
  The plan says "extend `NativeRuntimeHintsTest`", which sits in
  `adapter/in/web` alongside the DTO hints it covers. Putting ONNX resource
  patterns there would have made the web adapter the home of an infrastructure
  concern for no reason other than the test's address. `EmbeddingNativeRuntimeHints`
  is imported by `EmbeddingConfiguration` and tested by a sibling
  `EmbeddingNativeRuntimeHintsTest`; both hint sets keep the same shape.
- **The resource hint embeds the model in the binary, and that is a choice with
  a way out.** Registering `onnx/**` puts ~130 MB inside a native image. The
  alternative — `file:` URIs and the model shipped beside the binary — is
  documented in [`technical/native.md`](technical/native.md) rather than
  configured by default, because the default should be the one that works
  without extra deployment steps.
- **JNI reachability is declared, not proven, and the docs say so.** The
  `ai.onnxruntime` types the runtime instantiates from native code are
  registered by name, but only a GraalVM build can show that set is complete.
  Native status stays *native-ready, not validated* — v3 lot A added hints and a
  test, not a claim.
- **The drift sweep found more than the three known items.** `overview.md`, the
  vLLM comparison, `observability.md` and `README.md` still described embeddings
  as an Ollama concern, and `SPRING_AI_OLLAMA_BASE_URL` was still exported by
  CI, `scripts/dev.sh` and the plug & play compose for an auto-configuration
  A.1 excluded. Removed; `dev.sh` now passes `OLLAMA_BASE_URL`, which is the
  variable the egress provider actually reads.

## v3 lot A — A.4 (fixtures, baselines, calibration)

- **A latent bug from A.1, found by running the acceptance rather than assuming
  it.** `SpringAiTextEmbedder` still read `${spring.ai.ollama.embedding.options.model}`
  — a property A.1 deleted — so it resolved to `"unknown"` and stamped that on
  every calibration it fitted. The damage is subtle and exactly the kind this
  project cares about: staleness compares the stored model id to the current one,
  and `"unknown" == "unknown"` means **a calibration fitted on another model would
  have looked current forever**. Fixed to read `gatewai.embedding.model-id`, with
  a test (`InProcessEmbeddingModelTest.embedderReportsTheShippedModelId`) that
  fails if the provenance ever degrades to the default again.
- **`route-similarity-threshold` 0.60 → 0.25, chosen from a sweep, not from the
  calibrated value.** The sweep plateaus at 80–82 % between 0.15 and 0.30 and
  collapses above 0.35; the conformal fit lands independently at 0.2221, which
  corroborates the plateau. 0.25 sits **mid-plateau rather than at the argmax**
  (0.22 and 0.30 both score 82.0 %): this constant is the *fallback* used when no
  calibration is in force, and tuning a fallback to the peak of one labelled set
  is the mistake calibration exists to correct. It also keeps a few hand-overs
  (7 per 100) rather than routing everything by embedding, which preserves the
  designed escape hatch for prompts unlike any route.
- **The cache threshold stayed at 0.92 while the routing one moved.** Both are
  model-scale constants, but they are not the same kind of choice: the cache errs
  toward refusing, and on the new model 0.92 refuses *more* (FN 45.5 % → 61.4 %)
  while serving *fewer* wrong answers (FP 16.1 % → 14.3 %). Moving it would have
  bought hit rate with wrong answers, which is the trade this cache is documented
  not to make. The cost is recorded rather than hidden: 13 % hit rate calibrated,
  against 22 % on the old model.
- **Two baselines were loosened, which the plan says needs a justification.**
  `cacheFalseNegativeRateCalibrationMax` 0.57 → 0.66 and
  `cacheFalseNegativeRateTestMax` 0.48 → 0.64, because the same fixed threshold
  refuses more on the new model. The false-positive ceilings **tightened** in the
  same commit (0.21 → 0.15 and 0.18 → 0.16), which is the other half of that
  trade; the note is in `baselines.json` itself so the next reader finds it where
  the numbers are.

## v3 lot A — A.3 (model selection)

- **No `optimum-cli` export.** The plan says to export the candidates with
  `optimum-cli`; the measurement used the **pre-exported int8 ONNX** from the
  `Xenova/*` HuggingFace repos instead. It is the same artifact — an int8 export
  of the same checkpoint — without adding a Python/torch toolchain to a Java
  build, and it made the three candidates comparable within one session. If a
  future candidate has no published ONNX export, the toolchain comes back.
- **`multilingual-e5-small` was scored without its `query:` / `passage:`
  prefixes**, because that is how this gateway would run it: nothing in the
  cache advisor or the classifier prefixes text, and adding asymmetric prefixes
  would break ADR 0007's shared-vector assumption. The measurement is therefore
  of *the configuration we could ship*, not of the model's published ceiling.
  Stated in [`technical/evaluation.md`](technical/evaluation.md) so the number is
  not mistaken for a benchmark score.
- **The provisional model won, so nothing was re-recorded twice.**
  `paraphrase-multilingual-MiniLM-L12-v2` is confirmed on the numbers, not kept
  by inertia: 82.0 % calibrated routing against 73.0 % (EN-only MiniLM) and
  81.0 % (e5). The losing models were deleted from the tree after scoring.
- **The measurement runs left the tree on the committed v2 fixtures, on
  purpose.** Scoring a candidate means re-recording fixtures; after the last run
  the working copy was restored with `git checkout -- src/test/resources/eval/fixtures`
  so the re-record stays a **deliberate act in A.4**, as the plan asks. The
  harness is therefore still red on the model-mismatch assertion at the end of
  A.3, exactly as it was at the end of A.1.
- **Two findings handed to A.4 rather than fixed here.** The shipped
  `route-similarity-threshold=0.60` belongs to `nomic-embed-text`'s scale and
  hands 88 of 100 prompts to the heuristic on the new model (calibrated
  threshold: 0.2221); and α = 0.10 now costs 88.6 % cache false negatives. Both
  are configuration defaults that change `routing_config_version`, so they must
  move **before** the fixtures are recorded, not after — which is A.4's first
  step, not A.3's last.

## v3 lot A — A.2 (dimensions and vector schema)

- **`schema-validation=true` was tested as a default and rejected.** It looks
  like the fail-fast guard this project favours, and on an existing 768-wide
  table it is: startup dies with `Actual vector dimensions is 768, required
  vector dimensions is 384`. But Spring AI validates **instead of** creating, so
  the same flag fails a *fresh* install with `Table vector_store does not exist`.
  It ships commented in `application.properties` as a pre-flight check, and the
  default stays `initialize-schema=true` with no validation.
- **The silent-degradation path is now documented because it was reproduced.**
  Booting at 384 against a 768 table starts cleanly and answers every request;
  the cache simply never works again. Worth writing down precisely: the advisor
  catches the lookup error, traces `outcome = ERROR`, and returns before
  `cacheStore`, so the table does not grow either. The trace is what makes this
  detectable, which is a v2 batch 2 feature paying off in a v3 upgrade.
- **The full-stack acceptance run skipped the Ollama container.** The criterion
  says `docker compose -f docker-compose.yml up --build`; the image was built
  from that file, but the stack was started as pgvector + gateway with
  `--no-deps` and the `mock` profile. What A.2 is about is the **cache at 384
  from a jar-packaged model**, and starting Ollama would have added a ~3 GB
  chat-model pull that tests nothing here. Verified in that run: `vector(384)`,
  the HNSW cosine index, MISS→HIT, and the jar→`/tmp` model copy on a non-root
  user. Chat egress through the containerized Ollama is unchanged from v1 and
  covered by the plug & play stack.

## v3 lot A — A.1 (in-process ONNX embedding)

- **`dimensions=384` landed in A.1, not A.2.** A.1's own acceptance criterion is
  a completion "with cache + routing running", and the cache cannot store a
  384-dim vector in a table declared at 768 — the two are one change or neither
  works. A.2 keeps what it is actually about: the **upgrade path** for an
  existing 768-dim database, verified on real data, and the fresh-stack check.
- **DJL's PyTorch engine and model-zoo are excluded in `pom.xml`.** Not in the
  plan; found by watching the first test run download `libtorch_cpu.so` and
  friends (~250 MB) from `publish.djl.ai`. Inference is ONNX Runtime and DJL is
  only here for the tokenizer, so the engine is dead weight — and a network
  round trip on the very path this batch takes offline. Verified: the five
  embedding tests pass with both excluded.
- **The bundled `all-MiniLM-L6-v2` inside `spring-ai-transformers` is unusable.**
  Its `model.onnx` is a **133-byte Git LFS pointer**, and the auto-configuration
  defaults point at GitHub URLs, so the "bundled" default is a 90 MB download at
  first start. That is what forced shipping our own resource, and it is why
  `InProcessEmbeddingModelTest` asserts the committed files are megabytes rather
  than pointers.
- **The provisional model is multilingual, and it is fetched rather than
  committed.** The plan said not to default to `all-MiniLM-L6-v2`; measured int8
  exports are 22.9 MB (MiniLM, EN-only) against 118.3 MB for both multilingual
  candidates, which is over GitHub's 100 MB per-file limit. EN/FR from the first
  batch was worth more than a small repository, so the model is a **build-time
  dependency**: `download-maven-plugin` fetches it at `generate-resources`
  against a pinned SHA-256, into the classpath output, cached in `~/.m2`.

  **This replaced an earlier decision, and the reversal is the interesting
  part.** The batch first tracked the model with Git LFS. That is what the
  choice looked like before anyone priced it: GitHub Free allows **1 GB of LFS
  bandwidth per month, account-wide**, and at 135 MB per fetch with two CI jobs
  per push, the quota is gone after three or four pushes. The push that failed
  (a 112.8 MB blob committed before `git-lfs` was installed) forced the question
  early enough to answer it properly. The five lot-A commits were rewritten so
  the blob never entered the pushed history.
- **`EvalFixtureRecorderTest` lost its `integration` tag.** It needed Ollama;
  it now builds the same in-process model the application ships, from the same
  properties, so it needs no infrastructure at all. What kept an automated run
  from rewriting fixtures was never the tag but the `-Deval.record=true` gate,
  which stays. The command is now
  `./mvnw test -Dtest=EvalFixtureRecorderTest -Deval.record=true`.
- **Ollama stays declared in `compose.yaml`, under the `inference` profile.**
  The acceptance criterion said the service should be gone; removing it outright
  would also remove the default egress from `./mvnw spring-boot:run`, leaving a
  dev run unable to answer a real request without the `mock` profile or a cloud
  key. The profile satisfies what the criterion was for — `docker compose up`
  starts Postgres alone — without breaking the local-first promise.
  `scripts/dev.sh` passes `--profile inference`, because dev mode exists to send
  real requests.
- **New limit, worth stating: the tokenizer truncates at 128 tokens.** A prompt
  longer than that is embedded from its opening. For routing this is close to
  harmless (length is already a deterministic signal upstream); for caching long
  prompts it is a real limit, now in
  [`functional/limitations.md`](functional/limitations.md). A.3 should weigh it
  when comparing candidates.
- **A.1 ends with one red test, deliberately.** `EvaluationHarnessTest`
  refuses fixtures recorded against `nomic-embed-text` — the staleness detector
  doing its job. The plan puts the re-record in A.4 ("do the re-record
  deliberately, not reactively"), so it is not pulled forward here.
