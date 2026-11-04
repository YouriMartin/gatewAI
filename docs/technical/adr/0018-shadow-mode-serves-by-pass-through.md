# ADR 0018 — Shadow mode: serve by pass-through, decide by replaying the chain

**Status:** Accepted (v4 batch D.1). Amends [ADR 0016](0016-pass-through-what-the-chain-cannot-honour.md)
and the "the chain is the path for every request it can honour" rule of `AGENTS.md`:
a **shadow** client's requests take the pass-through path even when the chain could
honour them. The roadmap's ADR for D.3 (calibration on production labels) becomes
0019.

## Context

Nobody turns on a semantic cache in production on day one. Lot D lets a team put
gatewAI in front of real traffic, change nothing its clients receive, and read what the
cache and the router *would* have done. That needs two things at once:

- **the client's answer must be exactly what the upstream sent**: no cached answer, no
  re-routed model, no rewritten body;
- **the verdicts must be the real ones**: taken by the code that takes them in
  production, under the same calibration, scope and stickiness rules, not by a parallel
  re-implementation that could drift from it.

The pass-through path (ADR 0016) already does the first for the requests the chain
cannot honour. The advisor chain does the second, but only when it serves.

## Decision

1. **A mode per client**, `ENFORCE` (default, today's behaviour) or `SHADOW`, on
   `api_client` (`V14`), set by an admin (`PUT /v1/admin/clients/{id}/mode`) and
   recorded with the admin's id. The auth filter binds it into `RequestContext`.
2. **Every request of a shadow client is served by pass-through**, to the model it
   names: a registry id, or `auto` (or no model) for
   `gatewai.passthrough.default-model`. An unregistered name gets the existing
   `unknown_model` 400. Shadow never guesses a model, because a guess would be a
   routing decision applied to the client.
3. **The verdict is a replay of the chain.** Once the answer is relayed,
   `ShadowEvaluator` runs the request through the real advisor chain
   (`LlmClient.replay`) on a virtual thread. A last advisor, `ShadowReplayAdvisor`, sits
   where the model call would be and answers with the answer the client received. The
   cache and the router above it run unchanged: they decide, record, and on a miss the
   cache stores the real answer under the A.2 rules, so it warms up as it would in
   production.
4. **Every decision row carries the client's mode** (`cache_decision.mode`,
   `routing_decision.mode`). A `SHADOW` row is what the gateway would have done, never
   what it served. A shadow request the chain could have honoured records no
   pass-through decision of its own: one cache and one routing row per request, the
   replay's. A shadow request the chain cannot honour would have been passed through
   anyway, so its pass-through rows, stamped `SHADOW`, are the verdict.
5. **Bounded, never queued.** At most `gatewai.shadow.max-concurrent` replays run at
   once (default 16). Past that the replay is dropped and counted
   (`gatewai_shadow_decisions_total{outcome="dropped"}`): shadow work must never grow
   behind the traffic it observes, nor slow it down.
6. **Nothing else serves a shadow client through the chain.** An asynchronous job and
   an MCP tool call are refused (`unsupported_in_shadow_mode`), at the use-case level
   (`ChatCompletionService`, `DeferredChatService`), not silently enforced.

## Consequences

- The shadow verdict and the enforced decision come from the same code, so they cannot
  disagree by construction. The price is that the replay costs what the chain's
  decision costs: an embedding, a vector search, and a classifier call when the routing
  strategy calls an LLM.
- The served request does not wait for the evaluation. On the request thread, shadow
  adds a permit, a virtual-thread start and, on a stream, one JSON parse per event
  after the event has been sent to the client.
- A dropped replay leaves no decision row for that request: the drop is visible in the
  metric, not in the trace.
- Whether a would-hit would have been *right* is D.2's question, not this one's: D.1
  records the verdicts, it does not grade them.
