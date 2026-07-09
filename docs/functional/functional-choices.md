# Functional choices

The decisions below are about *what the product does and why*, not how it is
coded. For architectural/technical decisions see
[`../technical/`](../technical/).

## OpenAI format as the ingress standard

**Choice:** clients talk to gatewAI in the **OpenAI Chat Completions** format.

**Why:** it is the de facto market standard. Any existing OpenAI SDK or app works
by changing only the `base_url`, with no code rewrite — the single biggest driver
of adoption. The provider actually called (egress) is deliberately decoupled from
this format, so switching providers never breaks clients.

## Local-first egress, any provider mix by configuration

**Choice:** the gateway ships **local-first** — all three routing tiers map to
local Ollama models, so it works with zero API keys — and any mix of provider
instances (several Ollama/vLLM servers, Anthropic, OpenAI, any OpenAI-compatible
endpoint) is plain configuration. No vendor is required or privileged, and there
is no fallback provider: every routable model is an explicit registry entry.

**Why:** the product promise is *on-premise sovereignty*: a gateway that demands
a cloud API key before it starts undermines it. Local-first makes `docker
compose up` a complete, working deployment; cloud quality is one commented-out
registry entry away. The explicit provider-instance model (rather than one bean
per hardcoded vendor) is what makes "several local models/servers" and
"OpenAI-compatible anything" first-class. See the honest note in
[`limitations.md`](limitations.md).

## Cheapest tier that can still answer

**Choice:** the router picks the **cheapest tier** capable of answering, not the
"best" model every time.

**Why:** most enterprise traffic is routine and does not need a premium model. Cost
and carbon savings come from *not* using the expensive model when a cheaper one
suffices. The default is biased toward savings; when classification is uncertain,
the configurable fallback decides whether to err toward cost or toward quality.

## Heuristic routing by default, LLM routing opt-in

**Choice:** the default classifier is **pure heuristics** (length, code blocks,
keywords); an LLM-based classifier is available but not the default.

**Why:** heuristics are **free and instant** — no extra call, no latency, no cost
to decide where to route. That fits a gateway on the hot path. Teams that want more
nuance can switch to the LLM strategy at runtime, accepting its small overhead.

## 0.92 default similarity threshold

**Choice:** the semantic cache treats prompts as "the same" at cosine similarity
**≥ 0.92** by default.

**Why:** it is a deliberately **conservative** threshold — high enough to avoid
serving an answer to a genuinely different question, while still catching
rephrasings and repeats. It is a starting point, not a universal truth: tune it up
for correctness-critical use, down for more aggressive reuse.

## Per-client cache namespacing (on by default)

**Choice:** cached answers are **isolated per client** by default.

**Why:** in a multi-tenant setup, one client's answers should not leak to another,
and reporting/savings are meaningful per client. The cost is some duplication
across clients, accepted in exchange for isolation.

## "Avoided CO2 / €" measured against a premium baseline

**Choice:** the headline savings = what a **premium-by-default** call would have
cost and emitted, minus what actually happened after cache + routing.

**Why:** it answers the real business question — "what did caching and routing save
me versus naively sending everything to the best model?". It is an explicit,
defensible baseline rather than a vague absolute claim. The baseline assumption is
part of the methodology and should be stated when reporting.

## No expiry by default (TTL = 0)

**Choice:** cached answers do not expire unless you set a TTL.

**Why:** maximizes hit rate and savings for stable Q&A, which is the common case in
a demo/portfolio context. The trade-off is potential staleness — documented, and
fixable by setting a TTL per deployment. See [`limitations.md`](limitations.md).

## CSV/PDF reporting aimed at CSRD

**Choice:** the reporting exports are shaped for **CSR / CSRD** consumption
(aggregates over a date range, downloadable files).

**Why:** the differentiating story is turning a regulatory reporting constraint into
a concrete, quantifiable feature. The figures are directional today (placeholders),
but the *shape* of the deliverable matches what a sustainability team needs.

## Carbon-aware dispatch off by default

**Choice:** asynchronous, carbon-aware execution exists but is **disabled by
default**.

**Why:** it only makes sense for non-interactive, deferrable workloads, and running
the background worker has operational implications. It ships off so the default
behavior is simple and synchronous; enable it deliberately.

## Dashboard: minimal SPA, key held in the browser

**Choice:** a small Svelte single-page app that keeps the API key in the browser
and calls the same `/v1` API.

**Why:** consistent with the green stance (tiny bundle, no chart-library runtime)
and with the on-premise, internal-tool use case. Holding the key client-side is an
**accepted trade-off** for an internal tool; a session-based login would be the
next step for a public deployment.

## 60 requests/minute default rate limit

**Choice:** chat completions are limited to **60 req/min per client** by default.

**Why:** a sane guardrail protecting the gateway and upstream providers from runaway
clients, large enough not to bother normal interactive use. It is configurable and
can be disabled.
