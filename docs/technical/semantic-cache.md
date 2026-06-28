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
2. Build a `SearchRequest` with `query = userText`, `topK` and
   `similarityThreshold` from properties, plus an optional metadata filter (see
   below), and run `vectorStore.similaritySearch(...)`.
3. **Hit** (non-empty results): build a synthetic `ChatClientResponse` from the
   stored document and return it **without calling `chain.nextCall()`** — the
   short-circuit.
4. **Miss**: call `chain.nextCall(request)`, then `cacheStore(...)` the result on
   the way back, and return the real response.

```java
List<Document> hits = vectorStore.similaritySearch(searchBuilder.build());
if (!hits.isEmpty()) {
    return buildCachedResponse(hits.getFirst(), request.context());  // no LLM call
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
| `similarity-threshold` | `0.92` | cosine similarity for a hit; higher = stricter |
| `top-k` | `1` | nearest neighbours to consider |
| `ttl-minutes` | `0` | freshness window; `0` = no expiry |
| `client-namespacing` | `true` | isolate cache per client |

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
  conservative `0.92` default mitigates this; correctness-critical deployments
  should raise it and/or set a TTL. See the functional
  [`limitations.md`](../functional/limitations.md).
- **Cache quality** is bounded by the embedding model (`nomic-embed-text`).
