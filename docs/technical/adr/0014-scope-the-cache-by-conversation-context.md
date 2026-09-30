# ADR 0014 — Scope the semantic cache by conversation context

**Status:** Accepted (v4 batch A.2)

## Context

The semantic cache keys an answer on the **last user message only**:
`SemanticCacheAdvisor` embeds `Prompt.getUserMessage()`, which in Spring AI 2.0
returns the last `UserMessage`, and the embedding sees only the first 128 tokens
of it. The system prompt, the conversation history, the requested model, the
OpenAI `user` field and `stop` never reach the lookup. The only filters are the
API client (`client_id`) and the optional TTL.

v4 A.1 measured what that does to real traffic, on 100 labelled conversation
cases run through the real advisor: **64 of 64** cases that must not be served
were served, every one at similarity 1.0. That covers different histories ending
on "give me an example in Java", one question under two personas or output
languages, and one support template filled with two customers' orders. Long RAG
prompts that share their first 128 tokens were served too. It is not a threshold
problem: two different conversations that end on the same words **are** the same
vector. Across the end users of one API key, the `end-user-collision` case is a
data leak, not just a wrong answer.

Similarity on the last turn is still the right signal *inside* one conversation
context. "What's the difference with a readiness probe, then?" should be able to
reuse the answer to "And how is it different from a readiness probe?" when
everything before it is identical. What is wrong is letting the embedding carry
the context, because it cannot.

## Decision

**Similarity stays on the last user turn, but only inside an identical context.**
The context becomes an exact filter; the embedding never has to carry it.

### The scope key

`cache_scope` = SHA-256 over a versioned, canonical encoding of:

| Part | Why it is in the key |
|---|---|
| every system (and, from B.1, `developer`) message, in order | persona, output language, tenant and per-user data live here |
| every message before the last user turn — role and text, in order | a follow-up means something only with its history |
| the requested `model`, **when it is a registered model id** | the client asked for that model; an answer from another one does not honour the pin. Unregistered names (and B.2's `auto`) are routed, so they share a scope |
| the OpenAI `user` field, when present | end-user isolation inside one API key, for clients that send it |
| the `stop` sequences, when present | they change where the answer ends; a stored answer can contain a sequence this client stops on |

The encoding is a version tag (`gatewai-cache-scope/v1`) followed by
length-prefixed UTF-8 fields, so no text can forge a boundary. It uses **no
normalisation**: any byte of difference is a different context, which only ever
errs towards a refusal. It is a pure domain function, and a single-turn request
with no system prompt, no pin, no `user` and no `stop` gets one fixed scope. That
is exactly the population the v2/v3 pair set measures, so those metrics must not
move.

**Deliberately not in the key:**

- **Temperature, top-p, penalties, seed.** They say *how to sample*, not what a
  correct answer is. The cache already replays a fixed answer whatever the
  temperature; that is the product. Keying on them would split the cache along
  values SDKs set by default and disagree on (unset, 0.7, 1.0), costing hits for
  no correctness gain.
- **`max_tokens`.** A larger limit can be served a shorter stored answer, so it
  is a serve-time rule (below), not part of the identity.
- **Tools, `response_format`, `n > 1`, non-text parts.** They never reach the
  cache. Until B.3 they are refused with a 400, and from B.3 on they take the
  pass-through path.

### Lookup, store and serve

- **Lookup filter:** `client_id` (the existing namespacing) **and**
  `cache_scope`. A `vector_store` row written before this ADR has no
  `cache_scope` and can never match. `data-model.md` gives the optional cleanup
  statement.
- **Bypass**, traced as `BYPASS` with a reason: more than
  `gatewai.cache.max-history-messages` non-system messages (default **3**, so the
  first follow-up — user, assistant, user — stays cacheable). Past that, repeats
  of an identical context are rare, and the cache would store prompt text for
  nothing.
- **Long last turn — exact match only.** When the last user turn is longer than
  the embedding window, semantic matching is off: the lookup adds `turn_hash`
  (SHA-256 of the full turn text, stored on every entry) to the filter. Tokens
  are counted with the **embedding model's own tokenizer**, and the window is read
  from that tokenizer rather than hard-coded. The count sits behind a domain out
  port (`EmbeddingWindow`) implemented in `infrastructure.llm`, because the
  hexagonal rules forbid the cache adapter from using the llm adapter directly.
  *This departs from the roadmap's wording:* the case is traced as `HIT` or
  `MISS` with reason `EXACT_MATCH_ONLY`, not as `BYPASS`, because a lookup
  happens and can hit.
- **Store** only answers that ended normally — finish reason `stop` — and whose
  text is non-empty. Finish reasons are provider-native today (`STOP` from the
  OpenAI model, `end_turn` from Anthropic, `stop` from Ollama) and reach clients
  as-is. One domain normaliser maps them to the OpenAI values (`stop`, `length`,
  `tool_calls`, `content_filter`, unknown values unchanged). It is used by the
  cache **and** by the response, so a client now always receives the OpenAI value.
  Streaming stores on completion, from the last chunk's normalised reason.
- **Serve** a candidate only if its stored completion token count fits the
  incoming `max_tokens`, when one is set. Otherwise the result is a `MISS` with
  reason `MAX_TOKENS`.
- **Call and stream paths behave identically** (`adviseCall`, `adviseStream`,
  `storeStreamed`).

### Plumbing

`OpenAiMapper` drops `user` and `stop` today. They are carried to the advisor:
`stop` as `ChatOptions.stopSequences`, and `user` on the advisor chain's request
context. `user` is an ingress fact about this one request, not an option any
egress model receives.

### Trace and response

- `cache_decision` gains `reason` and `cache_scope` (a hash, never text) in
  migration `V10`, so a hit can be traced to the context it was allowed in.
  Reasons: `EMPTY_PROMPT` (the existing blank-prompt bypass, now named),
  `HISTORY_TOO_LONG`, `EXACT_MATCH_ONLY`, `MAX_TOKENS`. B.3 adds
  `PASSTHROUGH_<feature>`.
- Non-streaming responses carry `X-GatewAI-Cache: HIT|MISS|BYPASS` and
  `X-GatewAI-Model`. Streaming callers use `X-Request-Id` and the decision API.

## Alternatives considered

- **Embed the whole conversation.** Rejected. The window is 128 tokens, so a
  system prompt alone fills it and the last turn is cut off: the key would get
  *worse*. Two conversations that differ only in a customer's order number
  would still embed to ~0.99.
- **Scope by system prompt only.** Rejected. It fixes 32 of the 64 A.1 cases and
  leaves every follow-up collision.
- **Exact-match cache on the whole request.** Rejected. It is correct, but it
  gives up paraphrase hits, the one thing a semantic cache adds over an HTTP
  cache.
- **Rely on the `user` field for isolation.** Insufficient on its own: most
  clients do not send it, and it does nothing for personas or histories. It is
  in the key as an addition, not as the mechanism.
- **Never cache multi-turn requests.** Close to the chosen design, and simpler,
  but it loses the first follow-up in FAQ-style bots with a fixed system prompt —
  the traffic where a cache pays. The history limit keeps that case and drops the
  rest.

## Consequences

- **Correctness is bought with hits, and the price is shown.** A.1's
  `same_context_hit_rate` is 22 % today. Cases with two prior exchanges now
  bypass, so it can only stay or drop. The A.2 report prints before and after
  side by side, and `limitations.md` says what it costs.
- **The calibration keeps its meaning for single-turn traffic only.** The
  conformal threshold was fitted on single-turn pairs with no system prompt,
  which all fall in one scope. Inside other scopes the question is the same (last
  turn against last turn), but exchangeability with the calibration set is
  weaker, and `conformal-calibration.md` says so.
- **A selective filter meets an approximate index — measured, and the planned
  fix replaced.** pgvector's HNSW index finds its nearest neighbours first and
  filters afterwards. This ADR also makes a popular last turn ("give me an example
  in Java") be stored once per scope, so thousands of **identical** vectors
  accumulate, a region of the HNSW graph the search does not leave. Measured on
  pgvector 0.8.3 (the shipped image) with 23,000 entries, 3,000 of them one
  duplicated turn in 3,000 scopes, a per-scope lookup with `PgVectorStore`'s own
  query found its entry in **3 of 20** scopes. The mitigation this ADR first
  proposed, `hnsw.iterative_scan`, changed nothing (3 of 20 in `relaxed_order`
  and `strict_order`, even with `max_scan_tuples` raised), because iterative
  scans only reach what the graph connects. Raising `ef_search` to 1000 found 20
  of 20, but only while the duplicates stay below it. **What A.2 ships instead**
  is a GIN `jsonb_path_ops` index on `metadata::jsonb`, the exact expression
  `PgVectorStore`'s filter uses. The planner then reads a small scope through it
  and computes the distances exactly: **20 of 20**, 0.18 ms. For a large scope
  (every single-turn request without a system prompt shares one) it keeps
  choosing HNSW from statistics, and there the duplicate effect can still hide
  an entry. That costs a hit, never a wrong answer, and `limitations.md` states
  it. The index is created at startup by `PgVectorMetadataIndex`, not by
  Flyway, because Spring AI creates `vector_store` after Flyway has run. The
  SimpleVectorStore harness cannot see any of this, since it searches exactly.
- **The scope hash is pseudonymous, not anonymous.** It is an unsalted SHA-256,
  like `prompt_hash`. Anyone who can read the database and guess a system prompt
  (a template plus an order number) can confirm the guess. That reader can
  already see every cached last turn in clear text in `vector_store`, so this adds
  no new exposure. `decision-tracing.md` states it next to the existing note on
  what each store holds.
- **Legacy entries are dead weight until deleted.** They never match, they
  expire only under a TTL, and `data-model.md` gives the statement to drop them.
- **No new node-local state.** The scope lives in the shared vector store's
  metadata, so a cluster behaves like one node.
- **Clients see OpenAI finish reasons from now on** (`stop` instead of
  `end_turn` or `STOP`). That is a behaviour change, and it is the one the OpenAI
  format promised all along. `api-reference.md` records it.
