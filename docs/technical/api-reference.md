# API reference

All `/v1/**` endpoints require `Authorization: Bearer <key>`; `/v1/admin/**`
require an **admin** key. JSON bodies use **snake_case**. `POST
/v1/chat/completions*` is rate-limited (see [`security.md`](security.md)); `GET
/v1/models` is not.

## Chat completions (OpenAI-compatible)

### `POST /v1/chat/completions`

Request (`ChatCompletionRequest`):

```json
{
  "model": "auto",
  "messages": [{"role": "user", "content": "Hello"}],
  "temperature": 0.7,
  "max_tokens": 256
}
```

Honored: `model`, `messages`, `temperature`, `max_tokens` (or
`max_completion_tokens`, which wins when both are sent), **`stream`**,
`stream_options.include_usage`, `stop`, `user`, and since v4 B.1 `top_p`,
`presence_penalty`, `frequency_penalty` and `seed` — forwarded where the provider
supports them (table below). `model` is a **hint**: `auto` — or any name the registry
does not hold — is routed; a registered model id pins. [`GET /v1/models`](#get-v1models)
lists exactly those choices. Unknown fields are ignored, never rejected.

**Message shapes (v4 B.1).** `content` is a string, an array of parts, or `null`
(an assistant message that only calls tools). An array of `text` parts is joined
with `\n` and behaves exactly like the string form: same cache scope, same
embedding, same routing. Roles: `system`, `developer` (treated as `system`),
`user`, `assistant`, `tool`, `function`; anything else is a 400.

**What the advisor chain cannot serve is passed through, not degraded (v4 B.3,
[ADR 0016](adr/0016-pass-through-what-the-chain-cannot-honour.md)).** A request that
uses one of the features below is **marked**, and forwarded as the client wrote it:

| Feature | Marked when | Cache reason (`cache_decision.reason`) |
|---|---|---|
| tools | `tools` or legacy `functions` is non-empty | `PASSTHROUGH_TOOLS` |
| tool messages | a `tool`/`function` message, or an assistant message with `tool_calls`/`function_call` | `PASSTHROUGH_TOOL_MESSAGES` |
| non-text content | an `image_url`, `input_audio`, `file` or unknown part type in `content` | `PASSTHROUGH_IMAGE_INPUT`, `_AUDIO_INPUT`, `_FILE_INPUT`, `_OTHER_CONTENT` |
| `response_format` | any `type` other than `text` | `PASSTHROUGH_RESPONSE_FORMAT` |
| `n` | greater than 1 | `PASSTHROUGH_MULTIPLE_CHOICES` |
| `logprobs` | `logprobs: true` or `top_logprobs > 0` | `PASSTHROUGH_LOGPROBS` |
| `logit_bias` | non-empty | `PASSTHROUGH_LOGIT_BIAS` |

How a marked request is served:

- **Target.** A registered model id in `model` pins it. Anything else, `auto`
  included, goes to `gatewai.passthrough.default-model` (a registry model id;
  default: the first `cloud-premium` entry). It is **never classified**.
- **Body.** The original JSON, with only `model` rewritten to the target's id. Fields
  gatewAI does not model (`tool_choice`, `parallel_tool_calls`, `strict`…) reach the
  provider untouched. It is POSTed to the provider instance's OpenAI-format endpoint:
  `{base-url}/chat/completions` for `openai` (default `https://api.openai.com/v1`) and
  `openai-compatible`, `{base-url}/v1/chat/completions` for `ollama` and for
  `anthropic` (Anthropic's OpenAI SDK compatibility layer). No retry. A request to
  the default model fails over inside its tier on a connection failure, a timeout, a
  429 or a 5xx (v4 B.5, [ADR 0017](adr/0017-ordered-failover-inside-a-tier.md)); a
  pinned one does not unless `gatewai.failover.pinned=true`.
- **Answer.** The upstream status and body, as sent. The model in the body is the
  provider's own name. `X-GatewAI-Model` carries the registry id.
- **Streaming (v4 B.4).** With `"stream": true` the upstream's events are relayed one
  by one as they arrive, `[DONE]` included. gatewAI turns
  `stream_options.include_usage` on upstream to account the request. The usage-only
  chunk it adds is dropped before the client unless the client asked for it. The
  upstream is connected before the SSE response starts: a refusal is an HTTP error
  (table below), not an error event, and the stream carries `X-GatewAI-Cache` and
  `X-GatewAI-Model`. A client that disconnects cancels the upstream call. Its tokens
  are not accounted, and the disconnect is counted (`gatewai.passthrough.streams`).
- **Trace.** A `BYPASS` cache decision with the reason in the table (the first marked
  feature in declaration order). A routing decision with reason `PASSTHROUGH_PINNED`
  or `PASSTHROUGH_DEFAULT`, the target's tier and id, and no justification. Then a
  `request_log` row with the upstream's `usage`, accounted like any other request.
- **Refused instead (400, `"code": "unsupported_feature"`, `param` = the field):**
  - a marked request to `POST /v1/chat/completions/async` or over MCP;
  - a feature the target's endpoint is documented to ignore. For an `anthropic`
    target: `response_format`, `logprobs`, `logit_bias`, `input_audio` and `file`
    parts, `n > 1`. The message names the feature and the model; pin a model on
    another provider.

**Forwarded parameters, per provider type** — what the egress puts on the wire,
checked against a stub server for each type (`DelegatingChatModelWireTest`):

| Parameter | `openai` | `openai-compatible` | `ollama` | `anthropic` |
|---|---|---|---|---|
| `temperature`, `top_p`, `stop` | sent | sent | sent (`options`) | sent (`stop_sequences`) |
| `max_tokens` / `max_completion_tokens` | sent as `max_completion_tokens` | sent as `max_tokens` | sent as `num_predict` | sent as `max_tokens` (default 4096 when absent: the API requires it) |
| `presence_penalty`, `frequency_penalty` | sent | sent | sent (`options`) | ignored — the Messages API has none |
| `seed` | sent if it fits in 32 bits | sent if it fits in 32 bits | sent if it fits in 32 bits | ignored — the Messages API has none |
| `user` | never sent — a cache-scope key only | never sent | never sent | never sent |
| tools, non-text parts, `response_format`, `n > 1`, `logprobs`, `logit_bias` | pass-through: the body as sent | pass-through: the body as sent | pass-through: the body as sent | pass-through, except `response_format`, `logprobs`, `logit_bias`, audio and file parts, `n > 1`: refused (400), because the compatibility layer ignores them |

"Sent" means the field is in the request gatewAI makes; whether a self-hosted
server applies it is that server's business. The `mock` profile echoes and applies
nothing. The penalties and the seed are not part of the cache scope, like
temperature ([`semantic-cache.md`](semantic-cache.md)).

- **`stop`** (an array of strings) is forwarded to the model, and is part of the
  semantic cache's scope: an answer generated with stop sequences is only
  served to a request with the same ones.
- **`user`** is never sent to a model. It isolates end users inside one API key:
  an answer cached for one `user` is never served to another. Send it if one key
  serves several of your end users —
  [ADR 0014](adr/0014-scope-the-cache-by-conversation-context.md).

The semantic cache compares the last user message only **inside an identical
conversation context** — the system prompts, the history, a pinned model,
`user` and `stop` must all match exactly. Requests with more than three
non-system messages bypass it (see [`semantic-cache.md`](semantic-cache.md)).

**Streaming** (`"stream": true`): the response is `text/event-stream` — a series of
`data: {chat.completion.chunk}` events (each `choices[0].delta.content` is a token
delta; the terminal event sets `finish_reason`), ending with `data: [DONE]`. Cache
hits are replayed as a synthetic stream (no model call). With
`"stream_options": {"include_usage": true}`, one more chunk precedes `[DONE]`:
`"choices": []` and the `usage` of the whole answer, as OpenAI sends it. Other
chunks carry no `usage` field.

Response (`ChatCompletionResponse`, non-streaming):

```json
{
  "id": "chatcmpl-…",
  "object": "chat.completion",
  "created": 1735680000,
  "model": "claude-haiku-4-5",
  "choices": [
    {"index": 0,
     "message": {"role": "assistant", "content": "Hi!"},
     "finish_reason": "stop"}
  ],
  "usage": {"prompt_tokens": 8, "completion_tokens": 3, "total_tokens": 11}
}
```

`model` is the model that actually served the request. On a cache hit, `usage`
replays the original counts.

`finish_reason` uses the OpenAI vocabulary — `stop`, `length`, `tool_calls`,
`content_filter` — whichever provider answered. **Changed in v4 A.2:** before,
provider values passed through as-is (`end_turn` from Anthropic, `STOP` from the
OpenAI egress). A value with no OpenAI equivalent is still passed through
unchanged.

Response headers (non-streaming):

| Header | Value |
|---|---|
| `X-Request-Id` | the correlation id — the key of the decision API and the carbon record |
| `X-GatewAI-Cache` | `HIT`, `MISS` or `BYPASS` — what the semantic cache did (v4 A.2); always `BYPASS` on pass-through |
| `X-GatewAI-Model` | the model that produced the answer; on a hit, the one that produced it first; on pass-through, the registry id it was forwarded to |

Streaming responses from the chain carry `X-Request-Id` only: headers leave before
the cache outcome is known to the client-facing layer, so a streaming caller reads
the decision back by id. A streamed **pass-through** carries all three, since its
target is chosen before the stream opens.

### Errors

Failures on `POST /v1/chat/completions` return the **OpenAI error envelope**, so
client SDKs parse them like any other OpenAI error:

```json
{"error": {"message": "…", "type": "invalid_request_error",
           "param": null, "code": null}}
```

| Status | `type` | When |
|---|---|---|
| `400` | `invalid_request_error` | Malformed JSON, a body that is not the Chat Completions shape, or missing/empty `messages` |
| `400` | `invalid_request_error`, code `unsupported_feature` | A marked request on a path or a target that cannot honour it (above) |
| `400` | `invalid_request_error`, code `upstream_rejected` | The upstream answered 400, 413 or 422 (pass-through, and the chain since v4 B.5). The message names the status |
| `401` | — | Missing/invalid API key (Spring Security entry point, no body) |
| `429` | — | Rate limit exceeded (`Retry-After` header) |
| `429` | `api_error`, code `upstream_rate_limited` | The upstream answered 429, and failover had nothing left to try |
| `502` | `api_error` | The upstream rejected the gateway's credentials or model id (401, 403, 404, any other 4xx), answered 5xx, or could not be reached, and failover had nothing left to try |
| `504` | `api_error` | The upstream did not start answering within its instance's `read-timeout` (default 5 min), and failover had nothing left to try. For a stream, that is the wait for the first byte |
| `500` | `api_error` | Unexpected internal error |

Since v4 B.5 the chain reports provider failures with the same statuses and codes as
the pass-through path (until then an SDK error reached the client as a generic 500,
and Ollama's as a 503 after its own retries). When a tier has several models, those
statuses only appear once failover inside the tier has run out
([ADR 0017](adr/0017-ordered-failover-inside-a-tier.md)).

Upstream provider details are logged server-side but **not echoed** to the caller.
Streaming (`"stream": true`) reports failures inline on the SSE stream rather than
as this envelope, since the response is already committed when the egress fails.
A streamed pass-through is the exception up to its first byte: the upstream is
connected before the stream starts, so a refusal there gets the envelope.

## Models (OpenAI-compatible, v4 B.2)

Chat UIs fill their model picker from this endpoint. Same Bearer key as the rest of
`/v1/**`, no admin role; not rate-limited (it costs no model call).

### `GET /v1/models`

```json
{
  "object": "list",
  "data": [
    {"id": "auto",        "object": "model", "created": 0, "owned_by": "gatewai"},
    {"id": "qwen2.5:0.5b", "object": "model", "created": 0, "owned_by": "ollama"},
    {"id": "qwen2.5:1.5b", "object": "model", "created": 0, "owned_by": "ollama"},
    {"id": "qwen2.5:3b",   "object": "model", "created": 0, "owned_by": "ollama"}
  ]
}
```

- `auto` comes first: it means "let gatewAI route". It is not a registry entry, and
  the id is **reserved** — a registry entry with `model-id: auto` fails the startup.
- Then every `gatewai.models.registry` entry, in declaration order. `id` is the
  entry's `model-id` — the value that **pins** a request to it — and `owned_by` is the
  name of the provider instance that serves it (`gatewai.providers.<name>`), not the
  vendor.
- `created` is always `0`. The registry records no creation date, and the field must
  stay the same across restarts; read it as "unknown", not as 1970.

### `GET /v1/models/{id}`

One entry in the same shape, `auto` included. An id may contain `/`
(`Qwen/Qwen2.5-7B-Instruct`). An id the registry does not hold is a `404`:

```json
{"error": {"message": "The model 'gpt-9' does not exist.",
           "type": "invalid_request_error", "param": "model", "code": "model_not_found"}}
```

A `404` here does not mean a chat request naming that id fails: an unregistered name
in `POST /v1/chat/completions` is routed, like `auto`.

## Asynchronous, carbon-aware completions

### `POST /v1/chat/completions/async`
Same request body. Returns **`202 Accepted`** with a `DeferredJobResponse`:

```json
{"id": "…", "status": "queued", "chosen_zone": null, "result": null, "error": null}
```

### `GET /v1/chat/completions/async/{id}`
Returns the current `DeferredJobResponse`. `status` ∈
`queued|running|completed|failed`; when `completed`, `result` is a
`ChatCompletionResponse` and `chosen_zone` is set; when `failed`, `error` is set.
Unknown id → `404`, malformed id → `400`. Requires dispatch enabled to progress
(see [`carbon-aware-dispatch.md`](carbon-aware-dispatch.md)).

Since v3 lot B.2 the queue is a Postgres table, so a job **survives a restart** and
is readable from **any replica** — polling through a load balancer no longer
depends on reaching the node that took the submission. Note that the request is
stored in clear text until the job is deleted.

## Green reporting

### `GET /v1/reports/green?from=<iso>&to=<iso>&format=json|csv|pdf`
`from`/`to` are ISO-8601 instants. `json` (default) returns `GreenReportResponse`:

```json
{
  "from": "2026-01-01T00:00:00Z", "to": "2026-02-01T00:00:00Z",
  "total_requests": 1280, "cache_hits": 410, "cache_hit_rate": 0.32,
  "total_cost": 3.91, "total_cost_avoided": 5.12, "currency": "USD",
  "cost_by_currency": {"USD": 3.91}, "cost_avoided_by_currency": {"USD": 5.12},
  "pricing_basis": "MIXED", "requests_by_pricing_basis": {"SPLIT": 870, "NONE": 410},
  "pricing_note": "Pricing basis is mixed: 870 split, 0 blended, 410 not priced (cost zero because no price is declared).",
  "total_cost_eur": 3.91, "total_cost_avoided_eur": 5.12,
  "total_energy_kwh": 0.84, "total_grams_co2": 193.2,
  "total_grams_co2_avoided": 256.7,
  "model_mix": {"claude-haiku-4-5": 900, "claude-opus-4-8": 380},
  "emissions_scope": "PARTIALLY_EXCLUDED",
  "emissions_scope_note": "120 of 870 inference(s) were served by models excluded from scope (qwen2.5:3b); their energy and emissions are not included in the totals.",
  "accounted_requests": 750, "excluded_requests": 120,
  "excluded_model_mix": {"qwen2.5:3b": 120},
  "excluded_models": ["qwen2.5:3b"],
  "avoided_basis_note": "Avoided emissions are computed against the premium baseline, while …",
  "scope_basis": "Location-based Scope 2 only (the physical grid that served each request). Market-based accounting — net of renewable energy certificates and PPAs — is not computed.",
  "grams_co2_by_region": {"US-MIDA-PJM": 180.4, "FR": 12.8, "unattributed": 0.0},
  "grams_co2_by_provider": {"anthropic": 180.4, "vllm": 12.8, "ollama": 0.0},
  "assumed_regions": ["US-MIDA-PJM"],
  "assumed_region_note": "Region assumed, not known, for: US-MIDA-PJM. …"
}
```

The cost block (v4 B.6) never travels without what it is in. `currency` is the
ISO 4217 code of `total_cost` and `total_cost_avoided`. When the period spans a change
of `gatewai.billing.currency`, `currency`, `total_cost` and `total_cost_avoided` are
`null`, and `cost_by_currency` / `cost_avoided_by_currency` hold one figure per
currency, because no conversion is done. `pricing_basis` is `SPLIT`, `BLENDED`
(the deprecated `cost-per-1k-tokens`), `NONE` (no price declared: a zero cost means
*not priced*) or `MIXED`, with the counts in `requests_by_pricing_basis`.
`pricing_note` says the same in one sentence, including how many split-priced requests
did not report cached prompt tokens. **Deprecated:** `total_cost_eur` and
`total_cost_avoided_eur` are copies of `total_cost` / `total_cost_avoided` kept for
one release. Despite their names they hold the report's currency. See
[`green-accounting.md`](green-accounting.md#cost-split-prices-and-an-explicit-currency-v4-b6).

The `emissions_scope` block (v3 lot C.1) says what the CO2 totals cover, so a zero
is never read as a measured zero: `NO_ACTIVITY` | `ALL_ACCOUNTED` |
`PARTIALLY_EXCLUDED` | `ALL_EXCLUDED`. Models whose `energy-source` is
`not-accounted` — every self-hosted entry by default — are listed in
`excluded_model_mix`; cache hits are not counted there, since no inference ran.
`avoided_basis_note` is non-null only when an excluded actual sits next to a
non-zero avoided figure. See
[`green-accounting.md`](green-accounting.md#the-scope-boundary-v3-lot-c1).

The attribution block (v3 lot C.5) is built from what each row **stored about
itself**, not from the current registry, so a report of an old period still describes
that period. `grams_co2_by_region` keys are grid zones, with rows that carried no
region at all under `unattributed`; `assumed_regions` lists the zones whose region was
an operator declaration rather than a fact (the direct Anthropic and OpenAI APIs do not
disclose one). `scope_basis` is always present: these are **location-based Scope 2**
figures only.

`csv`/`pdf` return a downloadable file (`Content-Disposition: attachment`); both
state the scope boundary and the accounting basis on their face — the CSV in
`Report,…` header rows plus `Scope exclusions` and `GHG emissions by region` /
`by provider` sections, the PDF in its basis of preparation and in section 5
("Emissions attribution"). Bad date → `400`.

### `GET /v1/reports/green/series?from=<iso>&to=<iso>`
Returns an array of `GreenReportResponse`, **one per UTC day** (empty days
included). Range must satisfy `from < to`, max 366 days, else `400`.

## Admin — clients (`ROLE_ADMIN`)

### `POST /v1/admin/clients`
Body: `{"name": "my-app", "admin": false}`. Returns **`201`** with
`CreatedClientView`:

```json
{"client": {"id": "…", "name": "my-app", "enabled": true, "admin": false,
            "created_at": "…"},
 "api_key": "gw_…"}
```

`api_key` is shown **once**.

### `GET /v1/admin/clients`
Returns `[ApiClientView]` (`id, name, enabled, admin, created_at`) — never the key
or its hash.

### `POST /v1/admin/clients/{id}/revoke`
Revokes the client. Returns **`204 No Content`**.

## Admin — routing (`ROLE_ADMIN`)

### `GET /v1/admin/routing`
Returns `RoutingConfigView`:

```json
{"strategy": "embedding", "entry_length_threshold": 100,
 "premium_length_threshold": 500,
 "premium_keywords": ["refactor", "architecture", "security"],
 "route_similarity_threshold": 0.25,
 "cascade_margin_band": 0.02,
 "routes": [
   {"name": "casual-chat", "tier": "local",
    "examples": ["Hello, how are you today?", "Bonjour, comment ça va ?"]},
   {"name": "code-and-analysis", "tier": "cloud_premium",
    "examples": ["Refactor this Java service to use dependency injection"]}
 ]}
```

`strategy` ∈ `embedding` | `heuristic` | `llm` | `cascade`; `tier` ∈ `local` |
`cloud_entry` | `cloud_premium`. `embedding` and `cascade` require at least one
route.

`cascade_margin_band` (0..1, default 0.02) **rides on this payload without being
part of the routing config** (v2 batch 9). It is applied through its own port
method, so editing it alone leaves `routing_config_version` — and therefore the
conformal calibration fitted under that version — untouched. Everything else in
the body changes the version. See [`routing.md`](routing.md).

### `PUT /v1/admin/routing`
Body: a `RoutingConfigView`. Applies at runtime (next request); invalid config →
`400` (unknown strategy, `embedding`/`cascade` with no route, a band outside
`[0, 1]`). Returns the updated config.

Since v3 lot B.1 the edit is **persisted** (`routing_config` table), so it
survives a restart instead of being reset to `application.properties`. It is also
**propagated**: other replicas pick it up within
`gatewai.routing.config-sync-interval-ms` (5 s by default). A write that cannot be
persisted returns `5xx` and takes effect nowhere — deliberately, so a config can
never be live on one node only. See [`clustering.md`](clustering.md).

## Admin — calibration (`ROLE_ADMIN`)

### `GET /v1/admin/calibration`
What governs each decision right now. One entry per target (`CACHE`, `ROUTING`):

```json
[{"target": "CACHE", "status": "VALID", "applied": true,
  "effectiveThreshold": 0.9526, "fixedFallback": 0.92,
  "guarantee": "WRONG_ANSWER_RATE", "alpha": 0.10, "qhat": 0.9526,
  "sampleSize": 93, "embeddingModel": "paraphrase-multilingual-MiniLM-L12-v2",
  "routingConfigVersion": null, "calibratedAt": "2026-08-11T18:34:50Z"}]
```

`status` ∈ `VALID` | `STALE` | `ABSENT` | `DISABLED`. `effectiveThreshold` is
what is actually applied — `fixedFallback` whenever `applied` is false.

### `POST /v1/admin/calibration`
Fits both calibrations from the labelled set and stores them. Body optional:
`{"routingAlpha": 0.10, "cacheAlpha": 0.10}`; omitted values use the configured
defaults. Returns the same shape as `GET`.

Takes ~2 s on a local stack since v3 lot A — it embeds every labelled pair and
scores every labelled prompt, now in-process (it was ~15 s over HTTP). `409 calibration_failed` when the labelled set cannot support
the α asked for (the message says how many cases it would need); `400
invalid_alpha` when α is outside `(0,1)`. See
[`conformal-calibration.md`](conformal-calibration.md).

## Admin — decisions (`ROLE_ADMIN`)

Why a request went where it did (v2 batch 9). Admin-only: a trace names route
examples, cache entries and other requests' correlation ids, so it is strictly
more sensitive than the completions endpoint it explains.

### `GET /v1/admin/decisions?limit=20`
The most recent requests' decisions, newest first (`limit` capped at 200).
Merged across the cache and routing tables, so requests the cache answered — the
ones that never reach the router — are in the list.

### `GET /v1/admin/decisions/{correlationId}`
One request's decisions, **exactly as persisted, with nothing recomputed**:

```json
{"correlationId": "b3f1…", "at": "2026-08-14T09:12:11Z",
 "cache": {"outcome": "MISS", "similarityScore": 0.71, "runnerUpScore": 0.42,
           "threshold": 0.9526, "conformalStatus": "EMPTY_SET",
           "matchedEntryId": null, "matchedEntryAgeSeconds": null,
           "originCorrelationId": null,
           "embeddingModel": "paraphrase-multilingual-MiniLM-L12-v2",
           "reason": null, "cacheScope": "3cf52977134c…"},
 "routing": {"chosenTier": "CLOUD_PREMIUM", "chosenModelId": "qwen2.5:3b",
             "decisionReason": "MATCH", "strategy": "EMBEDDING",
             "effectiveStrategy": "EMBEDDING", "escalatedTo": null,
             "routingLatencyMs": 12, "justification": { },
             "confidence": {"topScore": 0.81, "margin": 0.12,
                            "threshold": 0.25,
                            "conformalSet": ["CLOUD_PREMIUM"], "alpha": 0.05},
             "promptHash": "…", "promptLength": 42,
             "embeddingModel": "paraphrase-multilingual-MiniLM-L12-v2",
             "routingConfigVersion": "c1bb83ddd18f7771",
             "conversationRouting": "STICKY", "classifiedTier": "LOCAL",
             "conversationFingerprint": "9d04e1b7a2c3…"}}
```

`routing` is **null on a cache hit** — the router never ran, and saying so is the
point. `cache.cacheScope` is the hash of the conversation scope the lookup ran in
(never text), and `cache.reason` says why a decision was not a plain lookup:
`EMPTY_PROMPT`, `HISTORY_TOO_LONG`, `EXACT_MATCH_ONLY` or `MAX_TOKENS` (v4 A.2).
`routing.conversationRouting` says how the conversation bore on the decision
([ADR 0015](adr/0015-conversation-sticky-routing.md), v4 A.3): `STICKY` (kept the
recorded model), `UPGRADED` (moved up for this turn and stays there) or
`FIRST_TURN_FLOOR` (no record; floored on the first user message). It is null on
a first turn or a pin. `classifiedTier` is what the last turn alone was worth, so
a premium `chosenTier` next to a `LOCAL` `classifiedTier` is a follow-up the
conversation held up. `conversationFingerprint` is the same hash on every turn of
one conversation. `404 decision_not_found` when nothing was recorded under that id (purged,
never recorded, or `gatewai.decisions.enabled=false`).

The correlation id is the one echoed on every response as `X-Request-Id`, and
the same key the carbon record uses.

### `POST /v1/admin/decisions/explain`
Exactly one of `{"correlationId": "…"}` or `{"prompt": "…"}` — both, or neither,
is `400 invalid_explain_request`. **Rate-limited** like the chat endpoints:
explaining a prompt costs one local embedding call per segment plus one.

```json
{"decision": { }, "attribution": {"status": "COMPUTED", "route": "code-and-analysis",
   "tier": "CLOUD_PREMIUM", "matchedUtterance": "…", "similarity": 0.81,
   "segments": [{"segment": "…", "contribution": 0.07, "share": 0.62, "rank": 1}]},
 "counterfactuals": {"status": "COMPUTED", "chosenRoute": "code-and-analysis",
   "chosenTier": "CLOUD_PREMIUM", "chosenUtterance": "…",
   "chosenSimilarity": 0.81,
   "alternatives": [{"tier": "LOCAL", "route": "casual-chat",
                     "nearestUtterance": "…", "similarity": 0.77,
                     "delta": 0.04, "rank": 1}]},
 "carbon": {"correlationId": "b3f1…"},
 "provenance": {"embeddingModelVersion": "paraphrase-multilingual-MiniLM-L12-v2",
                "routingConfigVersion": "c1bb83ddd18f7771",
                "calibrationDate": "2026-08-11T18:34:50Z", "status": "VALID"}}
```

The two inputs answer different questions, and the response says which:

| Input | `decision` | `attribution` / `counterfactuals` |
|---|---|---|
| `correlationId` | the stored trace | `PROMPT_UNAVAILABLE` — only hashes are stored, so a past request cannot be re-embedded |
| `prompt` | `null` — no request was made | computed against the rules in force **now** |

`provenance` is never omitted: every number above is relative to an embedding
model, a routing config version and a calibration. `carbon` **references** the
carbon record by correlation id rather than copying it — the figures live in the
green report. Method and limits:
[`attribution.md`](attribution.md), [`decision-tracing.md`](decision-tracing.md).

## MCP

`POST /mcp` — Model Context Protocol server (streamable HTTP), same Bearer auth.
Tools: `routed_chat`, `green_report`, `carbon_intensity`. See [`mcp.md`](mcp.md).

## Actuator

| Endpoint | Access |
|---|---|
| `GET /actuator/health`, `/actuator/info` | public |
| `GET /actuator/prometheus` | public (metrics scrape) |
| `GET /actuator/metrics` | authenticated |

## Status codes

`200` ok · `201` client created · `202` async accepted · `204` revoked · `400`
bad input · `401` missing/invalid key · `403` non-admin on admin route · `404`
unknown async id, unrecorded correlation id, or model id not in the registry
(`GET /v1/models/{id}`) · `409` calibration impossible on
the labelled set · `429` rate limited (`Retry-After`) · `500` internal error ·
`502`/`503`/`504` upstream provider error (chat ingress, see [Errors](#errors)).
