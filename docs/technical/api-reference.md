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

Accepted but **currently ignored**: `top_p`, `stream`, `n`, `stop`,
`presence_penalty`, `frequency_penalty`, `user`. Only `model`, `messages`,
`temperature`, `max_tokens` are forwarded; `model` is a **hint** (the router may
override it). There is **no streaming**.

Response (`ChatCompletionResponse`):

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
{"strategy": "heuristic", "entry_length_threshold": 100,
 "premium_length_threshold": 500,
 "premium_keywords": ["refactor", "architecture", "security"]}
```

### `PUT /v1/admin/routing`
Body: a `RoutingConfigView`. Applies at runtime (next request); invalid config →
`400`. Returns the updated config.

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
unknown async id · `429` rate limited (`Retry-After`).
