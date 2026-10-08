# ADR 0015 — Conversation-sticky routing

**Status:** Accepted (v4 batch A.3)

## Context

The router classifies the **last user message only**: `RoutingAdvisor` reads
`Prompt.getUserMessage()`, which in Spring AI 2.0 is the last `UserMessage`. Every
turn of a conversation is therefore routed as if it were the first. ADR 0014 fixed
the same blind spot in the cache. The router has two problems of its own:

- **A short follow-up in a hard conversation drops tier.** "ok, and in Java?" after
  a request to design a sharded event store classifies as `LOCAL`. The local model
  then answers a premium conversation it was never meant to carry, with the whole
  premium history as its prompt.
- **Changing model between turns forfeits the provider's prompt cache.** Anthropic
  and OpenAI both discount a repeated prompt prefix. A conversation that moves from
  one model to another, even inside one tier, pays full price on its whole history
  at every move.

Chat Completions is stateless. There is no conversation id on the wire; the
OpenAI `user` field identifies an end user, not a conversation. But clients
resend the history on every turn, so the **opening** of a conversation is the
same from one turn to the next.

## Decision

**A conversation keeps its model unless a later turn needs a higher tier.** The
classifier still decides on the last user turn alone, so its metrics and its
justification stay what they were. The conversation then sets a floor under that
answer.

### The fingerprint

`ConversationOpening` (domain) = SHA-256 over a versioned, length-prefixed
encoding (`gatewai-conversation/v1`, the same `FieldDigest` encoding as the
cache scope) of:

| Part | Why |
|---|---|
| every system message **before the first user message** | the persona and the task. A system message a client injects later in the conversation (a date, a retrieved document) does not change which conversation it is |
| the first user message | what the conversation is about |
| the first assistant message | two conversations that open with the same question usually diverge on the answer. The answer is also the gateway's own output, so it is the part a client is least likely to have produced on its own |

It is defined for requests that contain at least one assistant message. A first
turn has no fingerprint yet; it gets one once its answer is known.

### Record

- After a **routed** first turn, the router stores `(fingerprint, model_id, tier,
  created_at, last_seen_at)` in `conversation_affinity` (V11), keyed on the
  fingerprint computed with the answer.
- **Streaming** records on completion, with the concatenated deltas: that is the
  text the client sends back next turn.
- A first turn **served from the cache** is recorded by the cache, because the
  router never sees a hit. It records the cached answer and the **registry** model
  it was routed to. The provider reports its own model name, often a dated variant
  of the registry id, so the router now stamps the registry id on every response
  (`gatewai.routing.model` metadata) and the cache stores it as `routed_model`.
  An entry without it (a pinned answer, or one stored before this ADR) records
  nothing.
- **Pinned requests are never recorded and never floored.** The client chose the
  model; there is nothing to keep.

### Use

On a routed request with history:

| Case | Tier | Model | `conversation_routing` |
|---|---|---|---|
| a record exists, classified tier ≤ recorded | recorded | the **recorded model**, not just the tier | `STICKY` |
| a record exists, classified tier > recorded | classified | the tier's first model; the record moves up | `UPGRADED` |
| no record (older than A.3, past retention, first turn pinned) | max(last turn, first user message) | the tier's first model; recorded from now on | `FIRST_TURN_FLOOR` |

An unchanged tier keeps the recorded model because two models of one tier still
do not share a prompt cache. If the registry no longer declares that model on
that tier, the conversation moves to the tier's first model. A configuration
change moves a conversation within its tier, never off it.

The no-record case **is recorded**. Without that, a conversation with no record
would be re-floored on its first turn at every turn, and an easy third turn
after a hard second one would come back down.

The first user message is classified only when it differs from the last one. A
conversation that opens with an assistant greeting costs no second
classification.

### Concurrency and retention

- The write is one `INSERT … ON CONFLICT DO UPDATE`. It **never downgrades**,
  atomically: a tier at or above the recorded one keeps the recorded model and
  only moves `last_seen_at`. Two nodes writing one conversation at once leave the
  higher of the two. The rank comes from `ModelTier`'s declaration order.
- `gatewai.routing.conversation-ttl` (default 24 h since `last_seen_at`). The
  lookup ignores a record past it, and the upsert replaces one as if it were
  absent, so the purge does not enforce retention. The purge
  (`ConversationAffinityPurgeWorker`, hourly) runs under `LeaderLock` with its
  own `LeaderTask` (`CONVERSATION_AFFINITY_PURGE`, lock id 3) and only bounds the
  table's size.
- **Hashes and ids only.** No prompt or answer text is stored.
- The store never fails a request. A read failure is treated as no record and a
  write failure as a missing record next turn. Both degrade to the first-turn
  floor, never to an error.

### Trace

`routing_decision` gains three columns: `conversation_routing` (the case above;
null on a first turn or a pin), `classified_tier` (the last turn's own tier;
`chosen_tier` is now the tier the request was sent to) and
`conversation_fingerprint`. `decision_reason` is unchanged. It still summarises
what the classifier did, so it stays comparable with v2/v3 rows.

## Alternatives considered

- **A conversation id header.** Exact and cheap, but no OpenAI SDK sends one, and
  "any client works by changing the base URL" is the product. It can be added later
  as an override of the fingerprint without changing anything here.
- **Classify the whole history.** The embedding window is 128 tokens. A long
  history would classify on its opening and miss the turn that needs more. It
  would also change the single-turn metrics.
- **The OpenAI `user` field as the key.** It identifies an end user, who has many
  conversations. One hard conversation would pin every later one.
- **Floor on the first turn only, without a table.** Stateless and simple, but it
  cannot keep the *model* (only a tier), and it cannot remember an upgrade: turn 3
  would fall back below turn 2. It survives as the no-record fallback.
- **Keep the record in node memory.** A conversation's turns land on any node
  behind a balancer. Per-node state would be sticky only by luck, and v3 lot B
  exists to remove exactly that.

## Consequences

- A premium conversation stays premium through its short follow-ups, on one model.
  That costs more than routing each turn alone, by design. The cost is the price
  of not answering the second half of a hard conversation with the local model.
- Every routed multi-turn request does one indexed read and one upsert on the
  shared database. The decision latency in `routing_decision` includes them.
- A no-record conversation costs a second classification (of its first user
  message) once. After that it is recorded.
- **Clients that trim or rewrite history** (summarise old turns, drop the system
  prompt, strip whitespace from the answer) change the fingerprint. Their
  conversations fall back to the first-turn floor, which is the safe direction.
- **Conversations with identical openings share a floor**: same system prompt,
  same first question, same first answer. A cached first answer makes that more
  likely, not less. Both are listed in `limitations.md`.
- The single-turn routing metrics of the evaluation harness do not move. A
  request with no assistant message is routed exactly as before.
