# ADR 0017 — Ordered failover inside a tier

**Status:** Accepted (v4 batch B.5). Amends [ADR 0003](0003-ingress-egress-separation.md)
and the "no fallback provider" rule of `AGENTS.md`.

## Context

A tier may declare several registry entries, but until this ADR only the first one was
ever used: the router picked `findByTier(tier).getFirst()`, the pass-through default was
the first premium entry, and the other entries were dead configuration. A provider outage
therefore reached every client, even when the same tier listed a working alternative.

Two things in the egress made an outage worse than it had to be:

- **Retries hid the outage instead of routing around it.** Spring AI 2.0's
  `OllamaChatModel` wraps every blocking call in `RetryUtils.DEFAULT_RETRY_TEMPLATE`:
  10 retries on `TransientAiException` (5xx) and `ResourceAccessException` (connection
  failure, timeout), a 2 s initial delay multiplied by 5 and capped at 3 minutes
  (`spring-ai-retry` 2.0.0, `RetryUtils`). A dead Ollama held a request for more than
  15 minutes before the client heard anything. Streamed Ollama calls go through
  `WebClient` and are not retried. The OpenAI and Anthropic SDKs retry on their own
  (3 retries configured for `openai`/`openai-compatible`, the SDK default of 2 for
  Anthropic), on connection errors, 408, 409, 429 and 5xx.
- **Timeouts were either missing or wrong.** `openai`/`openai-compatible` instances got
  a 60 s timeout that the OpenAI SDK applies as the bound on the **whole** call
  (`Timeout.request`, which `read` also defaults to). Any chat call to them longer than
  60 s, a long streamed answer included, failed. Anthropic used the SDK's 60 s default
  the same way. Ollama used the JDK's defaults (no read timeout).

The roadmap asks for failover inside a tier, on both paths, without becoming a second,
silent router.

## Decision

**The registry entries of one tier, in a declared order, are that tier's failover chain.
A request that fails on one of them for a transient reason, before the first byte reaches
the client, is sent to the next. Never across tiers, never to an undeclared model, never
silently.**

### The order

`findByTier` returns the tier's entries in **failover order**:

1. entries with `gatewai.models.registry.<key>.priority` set, lowest first;
2. then the entries without one, in binding order.

The binding order is file order inside one properties or YAML file. It is **not** a
reliable order across property sources: Spring Boot binds a map from every source in
precedence order, so an entry declared by environment variables or a profile file comes
**before** the entries of `application.properties`, whatever its position in a
deployment's mental model. `priority` is the way to state the order when entries come
from several sources. Equal priorities keep binding order. The resolved chain of every
tier is logged at startup (`Tier local fails over in order: a → b`).

The first entry of the chain is what the router sends a new conversation to (A.3) and
what `gatewai.passthrough.default-model` defaults to for the premium tier, as before.

### When a request moves to the next entry

| Upstream outcome | Fails over? |
|---|---|
| connection failure (refused, unreachable host, reset before an answer) | yes |
| timeout (`connect-timeout` or `read-timeout` of the instance) | yes |
| HTTP 429 | yes |
| HTTP 5xx, Anthropic's 529 included | yes |
| any other 4xx (400, 401, 403, 404, 413, 422…) | **no**: the request or the configuration is wrong, and the next entry would get the same request |
| an error after the first byte reached the client | **no**: the answer has started, and nothing can be taken back |

"Before the first byte" means:

- **chain, blocking**: any failure of the call;
- **chain, streamed**: a failure before the first chunk is emitted downstream;
- **pass-through, blocking**: any failure of the call;
- **pass-through, streamed**: a failure while the stream is being opened, before the
  upstream answered 2xx. Once a 2xx stream is open the relay has begun, and a failure
  ends it like any other mid-stream failure (ADR 0016).

When the chain is exhausted, the client receives the **last** failure, mapped as ADR
0016 maps upstream failures (429, 502, 504). The upstream's body is never relayed.

### Which requests fail over

| Request | Fails over? |
|---|---|
| routed on the chain (classified, sticky or upgraded) | yes |
| pass-through to `gatewai.passthrough.default-model` | yes, inside the default model's tier |
| pinned to a registered model id, either path | **no**, unless `gatewai.failover.pinned=true`: the client asked for that model |

A pass-through candidate whose endpoint is documented to ignore one of the request's
marked features (ADR 0016) is **skipped**, never sent the request. The first target
still refuses it with a 400, as before: failover never turns a refusal into a different
model's answer.

### Timeouts, per provider instance

`gatewai.providers.<name>.connect-timeout` (default `PT10S`) bounds establishing the
connection. `gatewai.providers.<name>.read-timeout` (default `PT5M`) bounds the wait for
the provider to start answering: the time to the response headers, and for the SDK-based
clients (`openai`, `openai-compatible`, `anthropic`) also any silence between two reads.
Neither bounds a whole streamed answer: the ingress SSE timeout does that, as it already
did.

The read default is long on purpose. A blocking call returns nothing until the model has
generated the whole answer, which takes minutes for a large model on a CPU-only Ollama.
A timeout fails over and the provider still bills what it generated, so a short default
would pay twice for slow, healthy answers. Outages that matter show up as connection
failures, 429 or 5xx, which fail over at once. An operator who wants a faster timeout
failover sets a shorter `read-timeout` on that instance.

`gatewai.passthrough.timeout` is removed: the target instance's `read-timeout` now plays
its role on the pass-through path, so a provider has one timeout whichever path calls it.

### Retries: the gateway's failover is the only retry layer

Spring AI's retry and the SDKs' retries are **turned off** for every instance the
gateway builds: a no-retry `RetryTemplate` for Ollama, `maxRetries(0)` for the OpenAI and
Anthropic SDKs. Capping them instead would keep two retry layers that do not know about
each other. Each SDK retry on a dead instance is time the request is not spending on the
next entry, and the cooldown below needs to see each failure, not the last of three.

What a single-entry tier loses: an isolated 5xx or 429 used to be retried inside the
gateway, and now reaches the client. OpenAI-format clients retry those by default (the
official Java SDK retries connection errors, 408, 409, 429 and 5xx twice with backoff:
`openai-java` 4.39.1, `ClientOptions` and `RetryingHttpClient`, read 2026-10-22), so a
retry at the gateway as well would multiply attempts rather than add resilience.
A second entry in the tier is the way to ask for resilience.

### Cooldown and probe

Each registry entry has a health record, **per node, in memory**:

- a transient failure (the triggers above) increments its consecutive-failure count,
  whether or not the request could fail over: a pinned request's failure is still news
  about that model;
- a success resets the count;
- after `gatewai.failover.failure-threshold` consecutive failures (default 3) the entry
  is **skipped** for `gatewai.failover.cooldown` (default `PT30S`);
- once the cooldown has elapsed, **one** request is let through as a probe, and the
  other requests keep skipping the entry until the probe ends. A successful probe closes
  the record. A failed probe starts another cooldown.

Health is per **registry entry** (per model), not per provider instance. A 429 is often
per model, and a provider that is down trips each of its models after a few failures
anyway.

**When every entry of a chain is cooling down, the chain is tried as declared.** The
cooldown reorders, it never refuses: a gateway that answered "no model available" while
the provider had already recovered would be worse than one that pays a connection
attempt. That is also why a single-entry tier, which has nothing to fail over to, keeps
trying its only model.

Per node and in memory is acceptable: a cooldown is a short-lived optimisation that each
node rebuilds after a few failures, and sharing it would put a database write on every
failed upstream call. `clustering.md` lists it among the node-local state.

### Stickiness (A.3)

A conversation follows the model that **actually answered**. When a routed request fails
over, the conversation's record is rewritten to the answering model, so the next turn
starts there and does not pay the failed attempt again. The tier is unchanged: failover
never moves a conversation off its tier.

### Trace and metrics

- **`failover_event`**, one row per failed attempt that the request was allowed to fail
  over from: correlation id, path (`CHAIN` | `PASS_THROUGH`), tier, `from_model_id`,
  `to_model_id` (null when nothing was left to try), cause (`CONNECTION_FAILED`,
  `TIMEOUT`, `RATE_LIMITED`, `SERVER_ERROR`) and the upstream status when there was one.
  It is written off the request path, like the other decision tables, and purged with
  them. A request that did not fail over writes nothing here.
- **`gatewai_failover_total{tier,from,to,cause}`**, from the same objects. `from` and `to`
  are registry model ids, which the operator's configuration bounds, unlike the request
  `model` field. `to="none"` counts the exhausted chains.
- `routing_decision` is unchanged: it records where the request was **sent first**,
  before any call. `request_log`, the `X-GatewAI-Model` header and the green accounting
  name the model that **answered**. Joining on the correlation id reads the whole story.
- A panel in the committed Grafana dashboard shows failovers per tier and cause.

### Layout

- `domain.port.out.EgressHealth`: the health records, implemented in memory in
  `infrastructure/llm`.
- `domain.model.routing.FailoverSequence`: the candidates of one request, in order, with
  cooling entries skipped and tried last only when nothing else could be tried. Both
  paths use it.
- `domain.model.routing.FailoverCause`: which upstream failures are triggers.
- Chain: `RoutingAdvisor` marks a routed request as allowed to fail over, and
  `DelegatingChatModel` runs the attempts, since it is the only place that sees provider
  exceptions before they are wrapped. It turns those exceptions into
  `UpstreamFailureException`, which the chain's errors did not use before. A provider
  failure on the chain is now a 429, 502 or 504 with a stable error code instead of a
  generic 500.
- Pass-through: `PassThroughService` runs the attempts over `PassThroughClient`.

## Alternatives considered

- **Keep the SDK retries, capped at one.** It would keep a single-entry tier's
  behaviour on a transient 5xx. It also means two retry layers, a cooldown that only
  sees one failure out of two, and a slower failover on every multi-entry tier. A client
  SDK already retries a single-entry gateway's 5xx.
- **Fail over across tiers** (down to a cheaper tier, or up to a dearer one). Down
  serves a request the classifier judged too hard for that tier. Up silently spends
  money the operator never configured for that traffic. Both are routing decisions that
  the router did not take, which is what "never silent" excludes. An operator who wants
  a cloud fallback for the local tier declares a cloud entry **in** the local tier.
- **A circuit breaker that fails fast when every entry is open.** Standard, and it
  protects a struggling provider. It also refuses requests the provider may already be
  able to serve, and on a single-entry tier it turns three failures into 30 seconds of
  certain errors. The gateway is not the provider's load shedder.
- **Shared health across nodes** (a table, or Postgres advisory state). A write on every
  failed call, for state that each node relearns in three requests.
- **Health per provider instance.** Coarser: one model's 429 would cool down every model
  of its provider.

## Consequences

- A tier with two entries survives the loss of either, on both paths, streamed or not.
- The order of a tier is now behaviour, not decoration: reordering the registry changes
  where traffic goes. The startup log prints every tier's chain, and `priority` makes
  the order explicit when entries come from several property sources.
- A transient failure on a single-entry tier is no longer retried by the gateway.
- Long blocking calls to `openai`/`openai-compatible`/`anthropic` instances no longer
  fail at 60 s.
- A request that failed over is traced on three tables (where it was sent, why it moved,
  where it was answered). A report that only reads `routing_decision` sees the first
  choice, not the answering model.
