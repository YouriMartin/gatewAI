# API reference

All `/v1/**` endpoints require `Authorization: Bearer <key>`; `/v1/admin/**`
require an **admin** key. JSON bodies use **snake_case**. `POST
/v1/chat/completions*` is rate-limited (see [`security.md`](security.md)).

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

Honored: `model`, `messages`, `temperature`, `max_tokens`, and **`stream`**
(`model` is a **hint** — the router may override it). Accepted but ignored:
`top_p`, `n`, `stop`, `presence_penalty`, `frequency_penalty`, `user`.

**Streaming** (`"stream": true`): the response is `text/event-stream` — a series of
`data: {chat.completion.chunk}` events (each `choices[0].delta.content` is a token
delta; the terminal event sets `finish_reason`), ending with `data: [DONE]`. Cache
hits are replayed as a synthetic stream (no model call).

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

### Errors

Failures on `POST /v1/chat/completions` return the **OpenAI error envelope**, so
client SDKs parse them like any other OpenAI error:

```json
{"error": {"message": "…", "type": "invalid_request_error",
           "param": null, "code": null}}
```

| Status | `type` | When |
|---|---|---|
| `400` | `invalid_request_error` | Malformed JSON, or missing/empty `messages` |
| `401` | — | Missing/invalid API key (Spring Security entry point, no body) |
| `429` | — | Rate limit exceeded (`Retry-After` header) |
| `502` | `api_error` | Upstream provider rejected the request (auth, unknown model) or is unreachable |
| `503` | `api_error` | Upstream provider temporarily unavailable / rate-limited after retries |
| `500` | `api_error` | Unexpected internal error |

Upstream provider details are logged server-side but **not echoed** to the caller.
Streaming (`"stream": true`) reports failures inline on the SSE stream rather than
as this envelope, since the response is already committed when the egress fails.

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

## Green reporting

### `GET /v1/reports/green?from=<iso>&to=<iso>&format=json|csv|pdf`
`from`/`to` are ISO-8601 instants. `json` (default) returns `GreenReportResponse`:

```json
{
  "from": "2026-01-01T00:00:00Z", "to": "2026-02-01T00:00:00Z",
  "total_requests": 1280, "cache_hits": 410, "cache_hit_rate": 0.32,
  "total_cost_eur": 3.91, "total_cost_avoided_eur": 5.12,
  "total_energy_kwh": 0.84, "total_grams_co2": 193.2,
  "total_grams_co2_avoided": 256.7,
  "model_mix": {"claude-haiku-4-5": 900, "claude-opus-4-8": 380}
}
```

`csv`/`pdf` return a downloadable file (`Content-Disposition: attachment`). Bad
date → `400`.

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
           "embeddingModel": "paraphrase-multilingual-MiniLM-L12-v2"},
 "routing": {"chosenTier": "CLOUD_PREMIUM", "chosenModelId": "qwen2.5:3b",
             "decisionReason": "MATCH", "strategy": "EMBEDDING",
             "effectiveStrategy": "EMBEDDING", "escalatedTo": null,
             "routingLatencyMs": 12, "justification": { },
             "confidence": {"topScore": 0.81, "margin": 0.12,
                            "threshold": 0.25,
                            "conformalSet": ["CLOUD_PREMIUM"], "alpha": 0.05},
             "promptHash": "…", "promptLength": 42,
             "embeddingModel": "paraphrase-multilingual-MiniLM-L12-v2",
             "routingConfigVersion": "c1bb83ddd18f7771"}}
```

`routing` is **null on a cache hit** — the router never ran, and saying so is the
point. `404 decision_not_found` when nothing was recorded under that id (purged,
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
unknown async id or unrecorded correlation id · `409` calibration impossible on
the labelled set · `429` rate limited (`Retry-After`) · `500` internal error ·
`502`/`503` upstream provider error (chat ingress, see [Errors](#errors)).
