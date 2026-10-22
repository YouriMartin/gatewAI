# ADR 0016 — Pass through what the advisor chain cannot honour

**Status:** Accepted (v4 batch B.3). Amends [ADR 0002](0002-thin-gateway-advisor-chain.md)
and the "one processing chain" rule of `AGENTS.md`. Amended by
[ADR 0017](0017-ordered-failover-inside-a-tier.md) (v4 B.5): a request sent to the
default model fails over inside its tier, and the target instance's `read-timeout`
replaces `gatewai.passthrough.timeout`.

## Context

Since B.1 the ingress reads every message shape a client SDK sends, and **marks** the
requests the advisor chain cannot honour (`PassThroughFeature`): tools and tool messages,
non-text content parts, a `response_format`, `n > 1`, `logprobs`, `logit_bias`. Until
this ADR those requests got a 400 naming the feature. That kept the chain honest (it
never serves a request with a feature silently dropped), but it also meant that agents,
RAG pipelines and chat UIs with image upload could not use the gateway at all. Lot B
exists to close that gap.

Two things make the chain the wrong place to serve those requests:

- **The chain's decisions do not apply to them.** The cache keys an answer on the
  conversation's text (ADR 0014). An answer to a tool-calling turn depends on tool
  results and tool definitions, and an answer to an image depends on pixels. The router
  classifies text complexity. A tool loop classified onto the local tier because its
  last turn is short is exactly the failure a tool-using client cannot recover from.
- **The chain speaks Spring AI's `Prompt`, not the wire format.** Carrying tool
  definitions, `tool_choice`, `response_format` schemas, image parts and `logit_bias`
  through `Prompt` and back out through each provider's `ChatOptions` means
  re-implementing, per provider, a translation that the providers already offer in the
  format the client sent. Every field Spring AI does not model would be lost on the way,
  and that is the silent drop B.1 refuses.

Every egress type gatewAI supports already exposes an OpenAI-format Chat Completions
endpoint: OpenAI itself, every `openai-compatible` server, Ollama (`/v1/chat/completions`),
and Anthropic, through its OpenAI SDK compatibility layer.

## Decision

**The advisor chain stays the path for every request it can honour. A request it cannot
honour is forwarded as-is to one registered model, and is still authenticated,
rate-limited, traced and accounted.** There are two paths, and the B.1 markers are the
only switch between them.

### Which model

| Request `model` | Target |
|---|---|
| a registered model id | that entry (a pin, as on the chain) |
| anything else, `auto` included | `gatewai.passthrough.default-model` |

`gatewai.passthrough.default-model` is a registry **model id**, the same id a client pins
with and `GET /v1/models` lists. It defaults to the first registry entry of the highest
tier (`cloud-premium`), and an id the registry does not hold fails startup. A
pass-through request is **never classified**: nothing in the classifier's training says
anything about a request whose difficulty is in its tools or its image.

### Forwarding

The original JSON body, with only `model` rewritten to the target's model id, is POSTed to
the target's provider instance:

| Provider type | Endpoint | Auth |
|---|---|---|
| `openai` | `{base-url}/chat/completions`, base URL default `https://api.openai.com/v1` | `Authorization: Bearer` |
| `openai-compatible` | `{base-url}/chat/completions` | Bearer, when a key is set |
| `ollama` | `{base-url}/v1/chat/completions`, base URL default `http://localhost:11434` | none |
| `anthropic` | `{base-url}/v1/chat/completions`, base URL default `https://api.anthropic.com` | Bearer (Console API key) |

The Anthropic endpoint is its OpenAI SDK compatibility layer. Anthropic presents it as a
way to test and compare models, "not considered a long-term or production-ready solution
for most use cases", and it does not support prompt caching
(<https://platform.claude.com/docs/en/api/openai-sdk>, read 2026-10-20). Both are listed
in `limitations.md`. A native OpenAI → Messages translation is a later lot, on demand.

There is **no retry and no failover** on this path. Failover inside a tier is B.5 (ADR
0017), and it will apply to both paths at once.

### What an endpoint would silently drop is refused instead

The same page documents that the compatibility layer **ignores** `response_format`,
`logprobs`, `top_logprobs`, `logit_bias`, audio parts and file parts, and requires `n` to
be 1. Forwarding those requests would hand the client an answer to a different request,
which is the exact behaviour B.1 refuses. So the outbound adapter declares, per provider
type, the features its endpoint does not honour, and a pass-through request that uses one
of them on that target is refused with a 400 naming the feature and the model, before
anything is sent or traced:

| Provider type | Not honoured |
|---|---|
| `anthropic` | `response_format`, `logprobs`, `logit_bias`, `input_audio` parts, `file` parts, `n > 1` |
| `openai`, `openai-compatible`, `ollama` | nothing declared: the request goes upstream, and the upstream answers for it |

For a self-hosted OpenAI-compatible server, gatewAI cannot know what the model behind it
supports. The server is the authority, and its 4xx comes back to the client as below.

### The response

| Upstream | Client receives |
|---|---|
| 2xx | the status and the body, as sent |
| 400, 413, 422 | 400 `invalid_request_error`, code `upstream_rejected`, the upstream status named |
| 429 | 429 `api_error`, code `upstream_rate_limited` (OpenAI SDKs back off and retry on 429) |
| 401, 403, 404, any other 4xx | 502 `api_error`: the gateway's credentials or model id are wrong, not the client's request |
| 5xx, unreachable | 502 `api_error` |
| timeout (`gatewai.passthrough.timeout`, default 5 min) | 504 `api_error` |

The upstream body is never relayed on an error (the existing no-leak rule). It is logged
server-side, truncated. The answer carries `X-GatewAI-Cache: BYPASS` and
`X-GatewAI-Model: <target model id>`.

### Trace and accounting

- **`cache_decision`**: `BYPASS`, with reason `PASSTHROUGH_<FEATURE>`, the first marked
  feature in declaration order (the A.2 column). The cache is never consulted. The
  decision says so, rather than leaving a gap in the trace.
- **`routing_decision`**: `decision_reason` `PASSTHROUGH_PINNED` or `PASSTHROUGH_DEFAULT`,
  `chosen_tier` and `chosen_model_id` from the target, `justification` null (nothing
  was classified, as for `CLIENT_PINNED`). The prompt hash is the last user message's
  text, as on the chain.
- **`request_log` and green accounting**: through the **same code** as the chain, from the
  upstream `usage`. An answer without `usage` is logged with zero tokens and a warning.
  The zero means *not reported*, not *measured zero*, and `limitations.md` says so.
- **Hashes only.** Neither the body nor the answer is stored.
- A request refused or failed upstream gets no `request_log` row, as on the chain.

### Streaming (B.4)

A marked request with `stream: true` is relayed **event by event**:

- **Opened on the request thread.** The upstream is connected, and has answered 2xx,
  before any SSE response exists. A refusal is therefore an HTTP error in the OpenAI
  envelope, like the non-streamed path, and the stream carries the gateway's
  `X-GatewAI-Cache` and `X-GatewAI-Model` headers. `gatewai.passthrough.timeout`
  bounds that wait for the first byte, not the stream.
- **Relayed as it arrives.** Each event's `data` goes to the client when its blank
  line is read: nothing is buffered beyond one event, and `[DONE]` is relayed as the
  upstream sent it. Comments and `event:`/`id:`/`retry:` fields are not relayed.
  OpenAI-format servers put everything in `data`.
- **Usage.** `stream_options.include_usage` is turned on upstream, so the request can
  be accounted. The usage-only chunk it adds (no choices) is read, then dropped
  before the client unless the client asked for it. A chunk that carries usage
  *and* choices (vLLM's continuous usage stats) is relayed unchanged.
- **Accounting on completion**, through the same code as every other request.
- **A client that goes away cancels the upstream call**, either when a write to it
  fails or when the container reports the disconnect, whichever comes first. The
  tokens already produced are billed by the provider but cannot be accounted: their
  count only arrives in the last chunk. So the stream is **recorded as a disconnect**
  (a WARN log with the correlation id and the `gatewai.passthrough.streams` counter,
  `outcome=client_disconnected`), never as a zero-token `request_log` row, which
  would read as a measured zero. The counter has `completed` and `upstream_failed`
  beside it, so the share of streams the green report cannot see is a ratio, not a
  guess.
- An upstream that fails after the stream has started ends it the way the chain's
  streams end (an error after the response is committed). There is no envelope left
  to send.

### Scope of the path

- The deferred endpoint (`/v1/chat/completions/async`) and the MCP ingress keep refusing
  marked requests. Neither has a client that needs pass-through yet.
- The `mock` profile gets a built-in OpenAI-format echo in place of an HTTP upstream,
  including a scripted tool call, so the quickstart, the demo traffic and the replay
  harness (C.3, C.4, E.1) can exercise the path without a provider.

### Credentials

From this batch on, an `anthropic` provider instance whose key looks like a subscription
OAuth token (`sk-ant-oat…`, `sk-ant-ort…`) fails startup, with a message saying why.
Anthropic reserves those credentials for Claude Code and Claude.ai, and does not permit
third-party products to route requests through them
(<https://code.claude.com/docs/en/legal-and-compliance>, read 2026-09-28). The check covers
every declared instance, referenced by the registry or not, and never prints the key.

### Layout

`PassThroughUseCase` (`domain.port.in`) is implemented by `PassThroughService`
(`application.service`), which picks the target, records the decisions and accounts the
request. `PassThroughClient` (`domain.port.out`) is implemented in `infrastructure/llm`.
The domain carries the body as an opaque string. Every JSON operation (reading the body,
rewriting `model`, extracting `usage`) happens in an adapter.

## Alternatives considered

- **Carry the features through Spring AI.** Spring AI can return tool calls to the caller
  instead of executing them, and it models images. But each provider's options type
  models a different subset of `response_format`, `logprobs` and `logit_bias`, and every
  field outside that subset would be dropped. The result would be the same request,
  translated twice, with less in it.
- **Keep refusing.** Honest, and the state B.1 left. It fails lot B's goal: the clients
  that need these features (agents, RAG tools, chat UIs with uploads) are the clients a
  gateway is for.
- **Classify pass-through requests too.** It would keep local-first routing for tool
  calls. The classifier was trained and calibrated on text complexity, and a short tool
  result on the local tier is the failure this path exists to avoid. An operator who
  wants tools on a local model pins it or sets it as the default model.
- **Cache pass-through answers.** A tool-call answer is only correct for the exact tool
  results and definitions that produced it, and an image answer for those pixels. An
  exact-body cache would be safe but is a different feature, with its own invalidation
  questions. Not on the roadmap.

## Consequences

- Any OpenAI client works through gatewAI, including agents and chat UIs with uploads. A
  marked request is still authenticated, rate-limited, logged and counted in the green
  report.
- The cache and the router do **nothing** for those requests, by design, and the trace
  says so on every row. The default target is the premium tier, which is the expensive
  choice. That is deliberate, and an operator changes it with one property.
- There are two paths to keep consistent. They share the accounting code and the trace
  tables, so a report never has to know which path a request took, only to read it.
- Tools on Anthropic go through a compatibility layer that Anthropic does not call
  production-ready. The documented gaps are refused, not ignored.
