# Security

Authentication, authorization, key handling, rate limiting and request-context
propagation. Sources: `adapter/in/web/{SecurityConfig,
ApiKeyAuthenticationFilter, ApiKeyAuthentication, RateLimitFilter, RateLimiter}`,
`domain/model/{ApiKeyHasher, ApiClient, RequestContext}`,
`application/service/ApiClientAdminService`,
`infrastructure/persistence/AdminSeedRunner`.

## Authentication: API keys

- Clients authenticate with a **Bearer API key**: `Authorization: Bearer gw_…`.
- `ApiKeyAuthenticationFilter` (runs before
  `UsernamePasswordAuthenticationFilter`) reads the header, hashes the raw key, and
  looks up an **enabled** `ApiClient` by hash. On success it sets an
  `ApiKeyAuthentication` in the Spring `SecurityContext` and binds a
  `RequestContext(clientId)` Scoped Value around the rest of the filter chain.
- No match → no authentication is set; the request proceeds and is rejected by the
  authorization rules (401) unless it targets a public path.

## Key generation & storage

- Keys are generated in `ApiClientAdminService`: `"gw_"` + URL-safe Base64 of **32
  secure-random bytes** (`SecureRandom`).
- Only the **SHA-256 hash** of the key is stored (`ApiKeyHasher.hash`, 64 hex
  chars), as `api_client.api_key_hash` (unique). The raw key is returned **once**
  at creation and never persisted — a lost key cannot be recovered, only
  re-created.
- The same `ApiKeyHasher` is used on both sides (ingress lookup and admin
  creation), so they agree by construction.

## Authorization & roles

`SecurityConfig` defines the filter chain (CSRF disabled — it is a token API),
returning `401` via `HttpStatusEntryPoint` on auth failure. Rules:

| Path | Access |
|---|---|
| `/actuator/health`, `/actuator/info`, `/actuator/prometheus` | public |
| `/`, `/index.html`, `/assets/**`, favicons, `vite.svg` | public (dashboard shell) |
| `/v1/admin/**` | `hasRole("ADMIN")` |
| `/v1/**` | authenticated |
| `/mcp/**`, `/mcp` | authenticated |
| anything else | authenticated |

Admin status comes from the `ApiClient.admin` flag, mapped to a `ROLE_ADMIN`
authority in the auth filter.

## Bootstrap admin

`AdminSeedRunner` (an `ApplicationRunner`) seeds a `bootstrap-admin` client so the
system is usable without hand-inserting a key. Two modes:

- **Configured key** — when `gatewai.admin.api-key` (`GATEWAI_ADMIN_API_KEY`) is
  set, an admin is seeded with that exact key. **Idempotent**: created only if no
  client already has that key's hash, so restarts are safe and the key is the one
  you chose. The key value is not logged (you already have it).
- **Random key** — when unset, and only if no admin exists, one is created with a
  generated key logged **once** (`WARN: "...Admin API key (shown ONCE, copy it now):
  gw_…"`). Copy it; it is never shown again.

The default in-memory user from Spring's `UserDetailsServiceAutoConfiguration` is
**excluded** (auth is API-key based), so the misleading `Using generated security
password` log does not appear.

## Rate limiting

`RateLimitFilter` runs **after** authentication (so the client id is known) and
limits only `POST /v1/chat/completions*` (sync + async submit); status polls,
admin and report calls are not limited. Over the limit → **`429`** with a
`Retry-After` header and a JSON error body.

`RateLimiter` is a **Bucket4j** token bucket, **one bucket per client id**.
Default: `60` requests/minute (greedy refill), configurable via
`gatewai.ratelimit.{enabled,requests-per-minute}`.

### Two stores (v3 lot B.3)

`gatewai.ratelimit.store` picks where the buckets live. The limit itself and the
`Retry-After` it reports are defined once, on the `RateLimiter` interface, so the
two stores cannot drift into enforcing subtly different things.

| Store | Buckets in | Limit applies to | Cost per limited request |
|---|---|---|---|
| `memory` (default) | `ConcurrentHashMap` | **each process** | ~21–24 µs |
| `postgres` | `rate_limit_bucket` table | **the cluster** | ~3.4 ms p50 / 3.8 ms p95 |

Both numbers are measured, not estimated: `gatewai_ratelimit_check_seconds`
publishes them per store, with 0.5/0.95 quantiles enabled by default.

**Why `memory` is still the default.** It is correct and free on one node, which
is how a self-hosted gateway usually runs, and 3.8 ms is not nothing on a cached
response. The trade-off is that N replicas then grant N × the quota — measured,
not assumed: two nodes with a 6/min limit let **10 of 10** requests through.
Switching to `postgres` on the same setup gives exactly **6 allowed, 4 × `429`**
with `Retry-After: 9`. If you run more than one instance, set it.

**Is 3.8 ms material?** Against a real model call — hundreds of milliseconds to
seconds — it is under 1 %, so lot B.3 stops here rather than adding the
local-token-batching optimisation the roadmap held in reserve. It is ~15 % of a
*cache hit* under the `mock` egress, which is the shape of request where it would
show; if that ever matters, the metric is already there to prove it before any
complexity is added.

**Mechanics of the shared store.** Bucket4j's `SELECT … FOR UPDATE` strategy,
keyed on the client id as a string. Deliberately **not** `SKIP LOCKED` (unlike the
deferred-job claim): two requests from the same client *must* queue on the same
counter, which is what makes it one counter — requests from different clients take
different rows and never wait on each other. Deliberately **not**
`pg_advisory_xact_lock` either, which keys on a `bigint` and would mean hashing the
client id to 64 bits, where a collision silently merges two tenants' quotas.

**Editing the limit works.** A persisted bucket carries the bandwidth it was
created with, so `requests-per-minute` would otherwise be a setting that silently
does nothing until the rows were deleted by hand. The configuration version *is*
the limit, and the difference is credited (or debited) immediately: raising it
hands out the new headroom at once, and lowering it was observed to take effect
the same way — a bucket left over from a 100 000/min run started a 6/min node with
zero tokens.

**Failure mode: open.** If the bucket cannot be read, the request is allowed and
the failure is logged. A limiter whose bookkeeping is unavailable should not turn
that into an outage. This is largely theoretical: API-key authentication reads the
same database one filter earlier, so a database that cannot serve the limiter
cannot serve the request either.

## Request context propagation

`RequestContext.CURRENT` is a Java **Scoped Value** carrying `clientId` (and a
trace id slot). Bound by the auth filter, it is read downstream without parameter
passing by:

- `SemanticCacheAdvisor` — per-client cache namespacing/storage;
- `ChatCompletionService` — per-client attribution on the persisted `RequestLog`.

A separate `CarbonZoneContext.CURRENT` Scoped Value carries the chosen zone for
deferred jobs (see [`carbon-aware-dispatch.md`](carbon-aware-dispatch.md)).

## Honest security boundaries

- **API-key auth only** — no OAuth/SSO/session login.
- The **dashboard keeps the key in the browser** (local storage); acceptable for a
  self-hosted internal tool, not a public multi-user deployment.
- `/actuator/health|info|prometheus` are **public** for easy scraping — restrict by
  network/firewall in production.
- Provider keys and the ElectricityMaps token are supplied via environment
  variables and never committed.

See also the functional [`limitations.md`](../functional/limitations.md).
