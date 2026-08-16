# Implementation deviations

Decisions taken while implementing that depart from the plan they belong to, with
the reason. Per the v3 rules, review should read the deviation here rather than
rediscover it in a diff. Newest first.

Structuring decisions still go to [`technical/adr/`](technical/adr/README.md);
this file is for the smaller "the plan said X, the code does Y" record.

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
- **The provisional model is multilingual, and tracked with Git LFS.** The plan
  said not to default to `all-MiniLM-L6-v2`; measured int8 exports are 22.9 MB
  (MiniLM, EN-only) against 118.3 MB for both multilingual candidates, which is
  over GitHub's 100 MB per-file limit. Shipping EN/FR from the first batch was
  chosen over deferring the storage question, so `.gitattributes` tracks
  `*.onnx` and the bundled `tokenizer.json`, and both CI checkouts set
  `lfs: true`. **Cloning now requires git-lfs.**
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
