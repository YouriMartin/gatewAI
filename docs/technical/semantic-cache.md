# Semantic cache

The semantic cache is a custom Spring AI advisor that short-circuits redundant
requests before any model call. Source: `infrastructure/cache/SemanticCacheAdvisor`,
`CacheLookup`, `CachedResponses` and `SemanticCacheProperties`.

**Since v4 A.2 ([ADR 0014](adr/0014-scope-the-cache-by-conversation-context.md)),
similarity is compared on the last user turn only inside an identical
conversation context.** Before, the key was the last user message alone, and on
100 labelled conversation cases the cache served 64 of the 64 it should have
refused (see [Conversation scope](#conversation-scope-v4-a2) below).

## Where it sits

`SemanticCacheAdvisor implements CallAdvisor, StreamAdvisor` with `getOrder()` =
`Ordered.HIGHEST_PRECEDENCE` — it runs **first** in the advisor chain, before the
router, so a hit avoids both routing and the model call entirely.

## Lookup → hit/miss (call path)

`adviseCall(...)`:

1. Describe the request once (`CacheLookup`): the last user turn, the
   conversation **scope**, whether the turn fits the embedding window, the
   request's `max_tokens`. A blank prompt or a history longer than
   `max-history-messages` **bypasses** the cache (`chain.nextCall`), traced as
   `BYPASS` with its reason.
2. Build a `SearchRequest` with `query = last user turn` and `topK` from
   properties, filtered on the scope (see below), and run
   `vectorStore.similaritySearch(...)`. **No store-side threshold**: the store
   ranks, the advisor decides (see below).
3. Build the **conformal prediction set** — the candidates at or above the
   threshold in force — and record the decision (v2 batch 2 and 3).
   A candidate whose stored answer is longer than the request's `max_tokens` is
   refused (`MISS`, reason `MAX_TOKENS`). An unknown stored length (the provider
   reported no usage) is served.
4. **Hit**: build a synthetic `ChatClientResponse` from the stored document and
   return it **without calling `chain.nextCall()`** — the short-circuit.
5. **Miss**: call `chain.nextCall(request)`, then store the result on the way
   back — **only if it ended normally** (finish reason `stop`, see below) and is
   non-empty — and return the real response.

```java
CacheLookup lookup = describe(request);          // turn, scope, window, max_tokens
List<Document> candidates = search(lookup);      // ranked in the scope, not filtered
Verdict verdict = decide(candidates, lookup);    // threshold + set size + max_tokens
if (verdict.hit() != null) {
    return CachedResponses.call(verdict.hit(), request.context());  // no LLM call
}
ChatClientResponse response = chain.nextCall(request);
cacheStore(lookup, response);
return CachedResponses.withOutcome(response, CacheOutcome.MISS);
```

Every response the cache touches carries its outcome (`HIT`, `MISS`, `BYPASS`) in
its metadata; non-streaming callers get it as the `X-GatewAI-Cache` header.

## What is stored

On a miss, the advisor stores a `Document(userText, metadata)` in the vector store
(the embedding is computed by the configured `EmbeddingModel`). The metadata
captures everything needed to replay the answer and account for it later:

| Metadata key | Meaning |
|---|---|
| `cached_response` | the assistant answer text |
| `cached_model` | the model that produced it, as the provider named it |
| `routed_model` | the **registry** model id the router sent the request to (v4 A.3); absent on a pinned answer. A cached first turn starts its conversation on this model ([ADR 0015](adr/0015-conversation-sticky-routing.md)) |
| `cached_finish_reason` | finish reason, always `stop` since v4 A.2 — nothing else is stored |
| `cached_prompt_tokens` / `cached_completion_tokens` | original token counts |
| `created_at` | epoch millis (used for TTL filtering) |
| `client_id` | owning client (used for namespacing) |
| `cache_scope` | SHA-256 of the conversation scope the answer is valid in (v4 A.2) |
| `turn_hash` | SHA-256 of the full last user turn, for exact matching past the window (v4 A.2) |
| `correlation_id` | the request that produced the answer |

**Only an answer that ended normally is stored.** Finish reasons are normalised
to the OpenAI vocabulary first (`FinishReason`: `STOP` from the OpenAI model,
`end_turn` from Anthropic and `stop` from Ollama all become `stop`), and anything
else — `length`, `tool_calls`, `content_filter` — is not what the next asker
should get. The same normaliser now shapes the `finish_reason` every client
receives.

## Replaying a hit

`buildCachedResponse(...)` reconstructs a `ChatResponse` with the stored text,
model, finish reason and **replayed token counts**, and crucially sets
`LlmResponse.CACHE_HIT_METADATA_KEY = true` in the response metadata. That flag is
how the rest of the system knows it was a hit:

- `SpringAiLlmClient` reads it into `LlmResponse.cacheHit`.
- Green accounting then credits the **avoided** premium inference while recording
  **zero** real cost/energy/emissions (see [`green-accounting.md`](green-accounting.md)).

## Conversation scope (v4 A.2)

`CacheScope` (domain) hashes everything that decides what a correct answer is,
apart from the last user turn:

- every message except the last user turn — system prompts, the history — role
  and text, in order;
- the requested `model`, **when it is a registered id** (a pin; unregistered
  names are routed, so they share a scope);
- the OpenAI `user` field (end-user isolation inside one API key);
- the `stop` sequences.

The encoding is versioned and length-prefixed, with no normalisation: any
difference is a different context, which only ever errs towards a refusal.
Temperature, top-p, penalties and seed are deliberately left out — they say how
to sample, not what a correct answer is ([ADR 0014](adr/0014-scope-the-cache-by-conversation-context.md)).
A single-turn request with no system prompt, pin, `user` or `stop` always gets
the same scope, which is why the v2/v3 pair metrics are unaffected.

- **History limit**: more than `max-history-messages` non-system messages (3 by
  default: user, assistant, user) bypasses the cache — `BYPASS`,
  `HISTORY_TOO_LONG`.
- **Long turns**: a last turn longer than the embedding window — counted by the
  embedding model's own tokenizer, through the `EmbeddingWindow` port — is
  matched **exactly** (`turn_hash` joins the filter) and traced with reason
  `EXACT_MATCH_ONLY`. The embedding sees only the first 128 tokens, so two RAG
  prompts sharing a template would otherwise be one vector.
- **Legacy entries**: an entry written before v4 A.2 has no `cache_scope`, so it
  never matches. [`data-model.md`](data-model.md#vector-cache-pgvector) gives
  the statement to delete them.
- **The index behind it**: `PgVectorMetadataIndex` creates a GIN
  `jsonb_path_ops` index on `metadata::jsonb` at startup, so a small scope is
  searched exactly instead of through HNSW, which filters after searching and
  loses entries among identical vectors. The measurement is in the ADR.

## Filtering: scope, namespacing and TTL

`buildFilterExpression(scope, turnHash)` builds the `Filter.Expression`, all
parts AND-combined:

- **Scope** — always. This is also what makes a pre-A.2 entry unreachable.
- **Turn hash** — only for a turn past the embedding window.

- **Per-client namespacing** (`client-namespacing=true`, default): when a
  `RequestContext` is bound with a non-null clientId, restrict the search to
  documents with the same `client_id`. Tenants never see each other's cached
  answers.
- **TTL** (`ttl-minutes`, default `0`): when `> 0`, restrict to documents whose
  `created_at >= now − ttl`. `0` means **no expiry**.


## Configuration

`gatewai.cache.*` (`SemanticCacheProperties`):

| Property | Default | Meaning |
|---|---|---|
| `similarity-threshold` | `0.92` | cosine similarity for a hit; higher = stricter. Applied by the **advisor**, not the store. Since v2 batch 3 this is the **fallback**: a valid calibration supersedes it |
| `top-k` | `2` | candidates fetched per lookup; values below 2 are lifted to 2 |
| `ttl-minutes` | `0` | freshness window; `0` = no expiry |
| `client-namespacing` | `true` | isolate cache per client |
| `max-history-messages` | `3` | non-system messages above which a request bypasses the cache (v4 A.2) |

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
labels and the in-process model (v3 batch A.4), the calibrated threshold is
`0.9526` at α = 0.10, which serves wrong answers 14.3 % of the time — the same
rate as the fixed 0.92 on this model — at the cost of a lower hit rate (13 %
against 25 %). Method, numbers and limits:
[`conformal-calibration.md`](conformal-calibration.md).

## Traced decisions (v2 batch 2)

Every lookup writes a `cache_decision` row — `HIT`, `MISS`, `BYPASS` or `ERROR`,
with the winning score, the **runner-up's** score, the threshold in force and,
on a hit, the served entry's id, age and `origin_correlation_id` (see
[`data-model.md`](data-model.md)). Since v4 A.2 it also records the
`cache_scope` hash the lookup ran in and a `reason` when the decision was not a
plain lookup: `EMPTY_PROMPT` and `HISTORY_TOO_LONG` (bypasses),
`EXACT_MATCH_ONLY`, `MAX_TOKENS`.

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
  whole class is unchanged if you switch to Qdrant. The one pgvector-specific
  piece, the metadata index, is store-side tuning in `infrastructure.vectorstore`
  that the cache never calls; another store would need its own equivalent for
  selective filters.
- **Streaming (Phase 7.5)**: `adviseStream(...)` is fully implemented. On a **hit**
  it returns a **synthetic `Flux`** — the cached answer split into chunks — so the
  client gets the streaming UX with no model call. On a **miss** it streams through
  while aggregating the deltas and remembering the last finish reason, then stores
  the full answer on completion — under the same scope and store rules as the
  call path. The
  per-client store captures `clientId` eagerly (the `doOnComplete` runs on a
  reactive thread where the Scoped Value would be unbound).
- **False hits**: a high similarity can match a differently-intended prompt. The
  conservative `0.92` default (or a calibrated threshold) mitigates this; correctness-critical deployments
  should raise it and/or set a TTL. See the functional
  [`limitations.md`](../functional/limitations.md).
- **Cache quality** is bounded by the embedding model — since v3 lot A the
  in-process ONNX model (`paraphrase-multilingual-MiniLM-L12-v2`, 384 dim), which
  also means every stored vector is invalidated when it changes.
