# ADR 0011 — Run the embedding model in-process (ONNX) instead of on a model server

**Status:** Accepted

## Context

Every decision the gateway takes on the request path needs one vector: the
semantic cache searches with it, the router ranks routes against it, the cache
stores with it, and the explanation services re-embed variants of the prompt.
[ADR 0007](0007-memoized-embedding-model.md) reduced that to **one embedding call
per request** — but one call to *another container*.

That hop is the whole dependency:

- `./mvnw spring-boot:run` needed Postgres **and** Ollama, and a first start
  downloaded ~3 GB before the cache could answer anything;
- the cache and the router — the two features the project is about — were down
  whenever a model server was;
- the pitch "one jar, one Postgres, no API key" was false for the decision path,
  and the deployment story carried a second runtime nobody was routing *to* by
  default.

The embedding models in question are small. `nomic-embed-text` is 137 M
parameters; the 384-dim sentence transformers considered here are 22–118 M. This
is not a workload that needs a GPU server, it is a matrix multiply.

Three ways to remove the hop were available:

- **Keep Ollama, accept the dependency.** Zero work, and the status quo.
- **Call a hosted embedding API.** Removes the container, adds a vendor, a key
  and a network dependency on the request path — the opposite of the project's
  local-first, provider-agnostic stance ([ADR 0003](0003-ingress-egress-separation.md)).
- **Run the model inside the JVM**: Spring AI's `TransformersEmbeddingModel`
  (DJL + ONNX Runtime) implements `EmbeddingModel`, the very interface
  `MemoizingEmbeddingModel` decorates and `SpringAiTextEmbedder` depends on.

## Decision

The embedding model runs **in-process**, from an ONNX file shipped as a
classpath resource. Ollama stays as the default chat **egress** and leaves the
decision path entirely.

The swap is a bean: the auto-configuration builds a `TransformersEmbeddingModel`
from `spring.ai.embedding.transformer.*`, `EmbeddingConfiguration` wraps it in
the same `@Primary` memoizing decorator, and no advisor, classifier or
explanation service changed a line. ADR 0007 holds unchanged, and its assumption
survives because the chosen model is symmetric — an e5-style model with
`query:` / `passage:` prefixes would break case 3 and was rejected partly for
that.

The model is `paraphrase-multilingual-MiniLM-L12-v2`, int8-quantised, 384-dim,
**chosen on measurements** against `all-MiniLM-L6-v2` and `multilingual-e5-small`
(v3 batch A.3, numbers in [`../evaluation.md`](../evaluation.md)). DJL's PyTorch
engine is excluded from the dependency: inference is ONNX Runtime, DJL is here
for the tokenizer only, and left in it downloads ~250 MB of libtorch on the first
embedding — a network round trip on the path this decision exists to take
offline.

## Consequences

- **The gateway decides with one container.** Cache, routing, attribution and
  counterfactuals work with Postgres alone; `docker compose up` starts pgvector,
  and local inference is an opt-in profile. Cold start measured at **8.0 s**.
- **Decisions got an order of magnitude faster**: p50 34 ms → **3.2 ms**, p95
  44 ms → 8.2 ms. The old figure was an HTTP round trip; this one is an ONNX
  session in the same heap.
- **The artifact got much bigger**: the jar goes from **161 MiB to 349 MiB**
  (model 113 MiB, `onnxruntime` 89 MiB of multi-platform natives, tokenizer
  16 MiB, DJL tokenizers 18 MiB).
- **The model is a build-time dependency, not source.** It is over GitHub's
  100 MB per-file limit, and Git LFS would spend an account-wide 1 GB/month
  bandwidth quota on every clone and CI checkout, so it is fetched by
  `download-maven-plugin` against a pinned SHA-256 and cached in `~/.m2`. The
  repository stays small; a cold clean build needs network for the model exactly
  as it already does for Maven Central.
- **From a jar the model cannot be memory-mapped**, so Spring AI copies it to
  `spring.ai.embedding.transformer.cache.directory` at first start: **130 MB** of
  disk, once, per container. Running from an exploded classpath copies nothing.
- **The vector width changed, 768 → 384.** Every stored vector, calibration and
  evaluation fixture was invalidated by construction: `vector_store` must be
  dropped and refilled ([`../data-model.md`](../data-model.md)), both
  calibrations refit, and the fixtures re-recorded. The provenance columns and
  the harness's staleness assertions are what turned that into three loud
  failures instead of three silent wrong answers.
- **A similarity threshold does not survive a model change.** The shipped
  `route-similarity-threshold` moved 0.60 → 0.25 because 0.60 belongs to
  `nomic-embed-text`'s scale; kept as it was, it would have handed 88 of 100
  labelled prompts to the heuristic. Any future model swap must re-sweep it.
- **Quality is a wash, deliberately**: calibrated routing accuracy 83.0 % →
  82.0 %. One point is what the network hop was worth.
- **New limit**: the tokenizer truncates at **128 tokens**, so a long prompt is
  embedded from its opening. Harmless for routing (length is already a
  deterministic signal upstream), a real limit for caching long prompts.
- **Native image**: ONNX Runtime and the DJL tokenizer both extract JNI
  libraries at runtime, and the model is named by configuration, so all three
  need resource hints, declared in `EmbeddingNativeRuntimeHints`. They are
  unit-tested against the shipped configuration, and the
  native profile stays **native-ready, not validated** — a GraalVM build is the
  only thing that can prove the set complete.
- **Reversible.** Anything implementing `EmbeddingModel` can take its place; the
  Ollama starter is still on the classpath for egress, so reverting the decision
  path to a model server is a configuration change plus a re-record.
