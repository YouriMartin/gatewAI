# Troubleshooting

Every error a client can get from `POST /v1/chat/completions`, what it means and what
to do, then how to find one request again by its `X-Request-Id`. The error envelopes
below were captured from a running gateway on 2026-10-24 (version 0.0.1-SNAPSHOT,
commit `f6aad1a`, Ollama deliberately unreachable). The source of truth is
`ApiExceptionHandler`, `RateLimitFilter` and `SecurityConfig`. The full status table is
in [`api-reference.md`](../technical/api-reference.md#errors).

## Read the response first

Every response carries `X-Request-Id`, errors and `401`s included. Quote it in a bug
report. It is the key to everything in [Find a request](#find-a-request-by-x-request-id).
Send your own (`-H 'X-Request-Id: my-trace-123'`: letters, digits, `-`, `_`, `.`, up
to 64 characters) and gatewAI keeps it, so it joins your own trace.

Errors use the OpenAI envelope, which SDKs parse like any OpenAI error:

```json
{"error": {"message": "…", "type": "invalid_request_error", "param": null, "code": null}}
```

**Two responses are not in that envelope:** `401` has no body, and the gateway's own
rate limit (`429`) answers `{"error":"rate limit exceeded"}`.

## Your request

### `400` — `Malformed JSON request body.`

The body is not valid JSON. Usually a shell quoting problem with `curl -d`.

### `400` — `The request body does not match the Chat Completions format.`

The JSON is valid, but a field has the wrong type (`"messages": "hi"` instead of an
array, for example). The Jackson detail is logged by the gateway, not returned.

### `400` — `'messages' is a required property and must not be empty.`

Self-explanatory. A body that is a JSON array or a string rather than an object gets
`The request body must be a JSON object.`

### `400` — `messages[0].role 'wizard' is not one of system, developer, user, assistant, tool, function.`

An unknown role. `developer` is accepted and treated as `system`.

### `400` — code `unsupported_feature`, `param` = the field

The request uses something the advisor chain cannot honour — tools, tool messages,
non-text parts, `response_format`, `n > 1`, `logprobs`, `logit_bias` — on a path
that cannot forward it as-is. On `POST /v1/chat/completions` such a request is
**forwarded** (pass-through, [ADR 0016](../technical/adr/0016-pass-through-what-the-chain-cannot-honour.md)),
so you get this error in two cases only:

- **The wrong endpoint.** `POST /v1/chat/completions/async` and MCP's `routed_chat`
  have no pass-through:

  ```json
  {"error": {"message": "This request uses tools, which gatewAI forwards to the model as-is only on POST /v1/chat/completions. Send it to that endpoint, or send text-only messages without these fields.",
             "type": "invalid_request_error", "param": "tools", "code": "unsupported_feature"}}
  ```

- **A target that would silently ignore the field.** Anthropic's OpenAI compatibility
  layer documents `response_format`, `logprobs`, `logit_bias`, audio and file parts
  and `n > 1` as ignored, so a request that needs one of them and targets an
  `anthropic` instance is refused: `…which model '<id>' would silently ignore (…).
  Pin a model on another provider, or remove these fields.`

### A model name that does not exist is *not* an error

`"model": "gpt-9"` is routed, like `"auto"`. Only a **registered** model id pins a
model. `GET /v1/models` lists them, and `GET /v1/models/gpt-9` is a `404
model_not_found`. If you expected a pin and got routing, check the id against that
list: the response's `X-GatewAI-Model` header names the model that answered.

### `401` — no body

Missing `Authorization: Bearer <key>`, or a key that is unknown or revoked. The
bootstrap admin key is printed once in the logs at first start, or set by
`GATEWAI_ADMIN_API_KEY` ([`getting-started.md`](getting-started.md)). `/v1/admin/**`
with a non-admin key is a `403`.

### `429` — `{"error":"rate limit exceeded"}` with `Retry-After`

**gatewAI's own** per-client limit (`gatewai.ratelimit.requests-per-minute`, default
60), on chat completions and on `POST /v1/admin/decisions/explain`. Wait the number
of seconds in `Retry-After`. Several replicas each grant the full quota unless
`gatewai.ratelimit.store=postgres` ([`clustering.md`](../technical/clustering.md)).

## The provider behind the gateway

These appear only once **failover inside the tier** has nothing left to try
([ADR 0017](../technical/adr/0017-ordered-failover-inside-a-tier.md)). A tier with one
model fails on its first error. The provider's own error body is logged by the
gateway and never returned, so the cause is on the gateway side: see
[`failover_event`](#find-a-request-by-x-request-id).

| Status | Message | Usually | Look at |
|---|---|---|---|
| `400` code `upstream_rejected` | `The upstream model provider rejected the request (HTTP 422). Check the fields this model supports.` | the provider refused a field or a value (400, 413, 422) | the field list of that provider's API; context length |
| `429` code `upstream_rate_limited` | `The upstream model provider is rate-limiting requests. Please retry later.` | the **provider's** quota, not gatewAI's (the envelope tells them apart) | the provider console; a second entry in the tier |
| `502` | `The upstream model provider is unavailable or unreachable.` | connection refused, DNS, a 5xx | `base-url` of the instance; is the server up (`curl <base-url>`)? |
| `502` | `The upstream model provider rejected the request.` | the provider refused the **gateway's** credentials or model id (401, 403, 404) | `api-key` and `model-id` of the registry entry |
| `504` | `The upstream model provider did not answer in time.` | nothing within the instance's `read-timeout` (default 5 min; for a stream, the wait for the first byte) | a cold local model loading; the timeout |
| `500` | `An internal error occurred while processing the request.` | a gatewAI bug | the gateway log, then a bug report |

### `400` — code `unknown_model`

A **configuration** error, not a client one: the router chose a tier whose model is
not in the registry, or whose `provider` names an instance that is not declared
(`Model '<id>' maps to provider '<name>', which is not configured on this gateway.`).
For a pass-through, the request pinned nothing and there is no default target
(`… Set gatewai.passthrough.default-model.`). Most such mistakes fail at startup
instead. This one is what is left.

### A stream that stops early

With `"stream": true`, an error **before the first chunk** is the same HTTP error and
envelope as above (verified with an unreachable provider). An error **after** the
first chunk cannot change a status that is already sent. The stream simply ends,
**without** `data: [DONE]` and without an error event. `curl` reports `transfer
closed with outstanding read data remaining` (exit 18), and SDKs typically raise a
connection or decoding error. The gateway logs the provider error at `ERROR`, followed
by `No converter for [class java.util.LinkedHashMap] with preset Content-Type
'text/event-stream'` — that second line is the envelope it could not write into an
event stream, not a second problem.

## Find a request by `X-Request-Id`

The id is the `correlation_id` of everything the gateway records about the request:

| Where | What it tells you | Needs |
|---|---|---|
| `GET /v1/admin/decisions/{id}` | the cache decision (outcome, similarity, threshold, scope, reason) and the routing decision (tier, model, strategy, scores) | an admin key |
| Dashboard → *Why this decision* | the same, for recent requests; select a row for "why this decision" | an admin key |
| `failover_event` table | every failed attempt: path, tier, from → to, `cause` (`CONNECTION_FAILED`, `TIMEOUT`, `RATE_LIMITED`, `SERVER_ERROR`), upstream status | database access |
| `request_log` table | the model that answered, tokens, cost and carbon | database access; **no row for a request that failed** |

```bash
curl -s -H "Authorization: Bearer $ADMIN_KEY" \
  http://localhost:8080/v1/admin/decisions/a01ef18e-89c2-433e-ba68-add8ac6e4b5e
```

```sql
SELECT created_at, path, tier, from_model_id, to_model_id, cause, upstream_status
FROM failover_event WHERE correlation_id = 'a01ef18e-89c2-433e-ba68-add8ac6e4b5e';
```

A `404 decision_not_found` means nothing was recorded under that id: the request
never reached the chain (a `400`, `401` or `429` is refused before it), its rows were
purged (`gatewai.decisions.retention-days`, default 90), or recording is off
(`gatewai.decisions.enabled=false`).

**The gateway log is not indexed by request id.** Log lines do not carry it, except
the few that name it (a pass-through client disconnect, for example). To find the log
lines of a failure, match the timestamp in `failover_event.created_at` or the
decision's `at`.

## At startup

| Symptom | Cause | Fix |
|---|---|---|
| Startup fails naming an `anthropic` instance and a *subscription OAuth token* | the key starts with `sk-ant-oat`/`sk-ant-ort` — a Claude.ai plan token, which Anthropic does not permit third-party gateways to use | a Console API key ([`security.md`](../technical/security.md#provider-credentials-and-pass-through-v4-b3)) |
| Every request answers, but the cache never hits and decisions say `outcome = ERROR` | a `vector_store` table created at 768 dimensions by a pre-v3 version | `DROP TABLE vector_store;` before starting ([`data-model.md`](../technical/data-model.md#upgrading-to-v3-the-vector-cache-must-be-dropped-v3-lot-a)) |
| Startup fails with `gatewai.models.registry.<key>.model-id is an unresolved placeholder: set the environment variable GATEWAI_MODEL_…` | a preset that needs a variable it was not given — `openai-compatible` has no default model ids (v4 C.3) | set the named variable (all three `GATEWAI_MODEL_*`, and `OPENAI_COMPATIBLE_BASE_URL`) |
| Startup fails with `Provider 'openai-compatible' is openai-compatible but has no endpoint` | the quickstart's `openai-compatible` preset without `OPENAI_COMPATIBLE_BASE_URL` | set it to the root that serves `/chat/completions`, e.g. `https://…/v1` |
| Quickstart `ollama` preset: every request is `502 … unavailable or unreachable` | the container cannot reach the host's Ollama. On Linux Ollama listens on `127.0.0.1` only | start Ollama with `OLLAMA_HOST=0.0.0.0` (`systemctl edit ollama` → `Environment="OLLAMA_HOST=0.0.0.0"`), check a host firewall allows the Docker bridge, or set `OLLAMA_BASE_URL` |
| Quickstart `ollama` preset: every request is `502 … rejected the request` | a `GATEWAI_MODEL_*` the host's Ollama does not hold (the quickstart pulls nothing by default) | `ollama list`, then set the three variables to models it holds, or `OLLAMA_PULL_MODEL_STRATEGY=when_missing` to let the gateway pull them at startup |
| The first start takes minutes with the full stack | Ollama pulling the three default chat models (~3 GB) | wait for the health check, or use the `mock` profile or a cloud provider |
| `/actuator/health` is `503` | a component is down — the body names it (`db` is the usual one) | `GET /actuator/health` and read the `components` |
