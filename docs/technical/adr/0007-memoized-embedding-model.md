# ADR 0007 — Share the request embedding by memoizing `EmbeddingModel`

**Status:** Accepted

## Context

An uncached request embedded the same user text **three** times:

1. the semantic cache searches with it — `VectorStore.similaritySearch`;
2. the router classifies it — `EmbeddingComplexityClassifier`;
3. on a miss, the cache stores the answer as `new Document(userText, …)`, which
   embeds the text once more.

Three round-trips to Ollama for one vector: latency on the hot path, and the
occlusion attribution planned for v2 batch 7 multiplies embedding calls further.

The obvious fix — compute the vector once and hand it to the cache — is not
available. Spring AI 2.0's `SearchRequest` accepts a query **string** only; there
is no precomputed-vector entry point, and `PgVectorStore` embeds the query
itself in `getQueryEmbedding`. Passing a vector would mean reaching around the
`VectorStore` interface, which [ADR 0005](0005-depend-on-vectorstore-interface.md)
forbids.

## Decision

Solve it one level lower, at the `EmbeddingModel` bean instead of at the store.

`MemoizingEmbeddingModel` decorates the Ollama embedding model and is registered
`@Primary`, so the vector store and the classifier are both injected with it.
`RequestEmbeddingMemo`, a `ScopedValue` bound by `SpringAiLlmClient` around the
whole advisor chain, holds the per-request vectors. All three call sites above
reach the same memo, so the request embeds **once**.

Only single-text calls are memoized. `embed(List<String>)` is delegated
untouched — that is route-example indexing, which has its own cache. The
single-document form of `embed(List<Document>, …)` *reads* the memo (case 3
above) but never writes to it.

## Consequences

- **`VectorStore` stays the only vector abstraction.** ADR 0005 holds; a swap to
  Qdrant remains a dependency + config change.
- **No component knows about the sharing.** The cache advisor and the classifier
  are unchanged; they simply stop paying twice.
- **An absent memo is never incorrect, only slower.** Outside a bound scope — a
  classifier call from a test, an embedding computed after the scope closed — the
  decorator delegates exactly like the undecorated model. There is no code path
  where a missing memo produces a wrong vector.
- **Bounded on purpose.** The memo caps its entries, so batch 7's occlusion
  (which embeds many variants of one prompt) cannot turn it into a per-request
  heap of 768-float vectors.
- **Assumption to hold:** reusing a query vector as a document vector is exact
  only because nothing here sets per-call embedding options and the Ollama model
  applies no query/document prefix. Introducing asymmetric embedding options
  would invalidate case 3 and must be revisited here.
- Measured on the local stack: one uncached `/v1/chat/completions` triggers **1**
  `POST /api/embed` against Ollama, down from 3.
