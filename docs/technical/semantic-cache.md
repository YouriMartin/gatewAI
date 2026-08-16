# Semantic cache

The semantic cache is a custom Spring AI advisor that short-circuits redundant
requests before any model call. Source: `infrastructure/cache/SemanticCacheAdvisor`
and `SemanticCacheProperties`.

## Where it sits

`SemanticCacheAdvisor implements CallAdvisor, StreamAdvisor` with `getOrder()` =
`Ordered.HIGHEST_PRECEDENCE` — it runs **first** in the advisor chain, before the
router, so a hit avoids both routing and the model call entirely.

## Lookup → hit/miss (call path)

`adviseCall(...)`:

1. Extract the user text from the prompt. If null/blank, pass through
   (`chain.nextCall`).
2. Build a `SearchRequest` with `query = userText` and `topK` from properties,
   plus an optional metadata filter (see below), and run
   `vectorStore.similaritySearch(...)`. **No store-side threshold**: the store
   ranks, the advisor decides (see below).
3. Build the **conformal prediction set** — the candidates at or above the
   threshold in force — and record the decision (v2 batch 2 and 3).
4. **Hit**: build a synthetic `ChatClientResponse` from the stored document and
   return it **without calling `chain.nextCall()`** — the short-circuit.
5. **Miss**: call `chain.nextCall(request)`, then `cacheStore(...)` the result on
   the way back, and return the real response.

```java
List<Document> candidates = lookup(userText);   // ranked, not filtered
Verdict verdict = decide(candidates);           // threshold + set size decide
if (verdict.hit() != null) {
    return buildCachedResponse(verdict.hit(), request.context());  // no LLM call
}
ChatClientResponse response = chain.nextCall(request);
cacheStore(userText, response);
return response;
```

## What is stored

On a miss, the advisor stores a `Document(userText, metadata)` in the vector store
(the embedding is computed by the configured `EmbeddingModel`). The metadata
captures everything needed to replay the answer and account for it later:

| Metadata key | Meaning |
|---|---|
| `cached_response` | the assistant answer text |
| `cached_model` | the model that produced it |
| `cached_finish_reason` | finish reason (default `stop`) |
| `cached_prompt_tokens` / `cached_completion_tokens` | original token counts |
| `created_at` | epoch millis (used for TTL filtering) |
| `client_id` | owning client (used for namespacing) |

## Replaying a hit

`buildCachedResponse(...)` reconstructs a `ChatResponse` with the stored text,
model, finish reason and **replayed token counts**, and crucially sets
`LlmResponse.CACHE_HIT_METADATA_KEY = true` in the response metadata. That flag is
how the rest of the system knows it was a hit:

- `SpringAiLlmClient` reads it into `LlmResponse.cacheHit`.
- Green accounting then credits the **avoided** premium inference while recording
  **zero** real cost/energy/emissions (see [`green-accounting.md`](green-accounting.md)).

## Filtering: namespacing and TTL

`buildFilterExpression()` builds an optional `Filter.Expression`:

- **Per-client namespacing** (`client-namespacing=true`, default): when a
  `RequestContext` is bound with a non-null clientId, restrict the search to
  documents with the same `client_id`. Tenants never see each other's cached
  answers.
- **TTL** (`ttl-minutes`, default `0`): when `> 0`, restrict to documents whose
  `created_at >= now − ttl`. `0` means **no expiry**.

The two filters are AND-combined when both apply.

## Configuration

`gatewai.cache.*` (`SemanticCacheProperties`):

| Property | Default | Meaning |
|---|---|---|
| `similarity-threshold` | `0.92` | cosine similarity for a hit; higher = stricter. Applied by the **advisor**, not the store. Since v2 batch 3 this is the **fallback**: a valid calibration supersedes it |
| `top-k` | `2` | candidates fetched per lookup; values below 2 are lifted to 2 |
| `ttl-minutes` | `0` | freshness window; `0` = no expiry |
| `client-namespacing` | `true` | isolate cache per client |

## The calibrated threshold and the prediction set (v2 batch 3)

The `0.92` above was a guess. When a calibration is in force it is replaced by a
quantile fitted on labelled pairs, and the **size of the prediction set** decides:

| Set | Outcome | `conformal_status` |
|---|---|---|
| empty | miss, call the model | `EMPTY_SET` |
| one candidate | serve it | `SINGLETON` |
| more than one | **do not serve** | `AMBIGUOUS` |

Refusing an ambiguous set is the point, not an edge case: if two stored answers
both look right for this query, at most one of them is, and taking the higher
score is guessing with the user's answer.

With no valid calibration the advisor degrades to exactly the previous behaviour
— fixed threshold, best candidate wins — and records `NOT_CALIBRATED` or
`STALE_CALIBRATION` so a degraded decision stays distinguishable. On the shipped
labels, the calibrated threshold is `0.9423` at α = 0.10, which serves wrong
answers 12.5 % of the time against the fixed threshold's 16.1 %, at the cost of a
lower hit rate. Method, numbers and limits:
[`conformal-calibration.md`](conformal-calibration.md).

## Traced decisions (v2 batch 2)

Every lookup writes a `cache_decision` row — `HIT`, `MISS`, `BYPASS` or `ERROR`,
with the winning score, the **runner-up's** score, the threshold in force and,
on a hit, the served entry's id, age and `origin_correlation_id` (see
[`data-model.md`](data-model.md)).

This is why the threshold moved out of the store. Filtered server-side, a
rejected candidate is invisible: neither the runner-up margin nor the
near-misses could ever be observed, and both are exactly what batch 3 calibrates
on. A 0.93 hit whose runner-up scored 0.92 is a coin flip; the same hit against
0.41 is not — and only the advisor-side comparison can tell them apart.

Tracing is best-effort by construction: writes go off the request path and a
failing store increments `gatewai.decisions.write.failures` instead of failing
the completion.

## Design decisions & trade-offs

- **Reversibility**: the advisor depends only on `VectorStore`, not pgvector. The
  whole class is unchanged if you switch to Qdrant.
- **Streaming (Phase 7.5)**: `adviseStream(...)` is fully implemented. On a **hit**
  it returns a **synthetic `Flux`** — the cached answer split into chunks — so the
  client gets the streaming UX with no model call. On a **miss** it streams through
  while aggregating the deltas, then stores the full answer on completion. The
  per-client store captures `clientId` eagerly (the `doOnComplete` runs on a
  reactive thread where the Scoped Value would be unbound).
- **False hits**: a high similarity can match a differently-intended prompt. The
  conservative `0.92` default (or a calibrated threshold) mitigates this; correctness-critical deployments
  should raise it and/or set a TTL. See the functional
  [`limitations.md`](../functional/limitations.md).
- **Cache quality** is bounded by the embedding model — since v3 lot A the
  in-process ONNX model (`paraphrase-multilingual-MiniLM-L12-v2`, 384 dim), which
  also means every stored vector is invalidated when it changes.
