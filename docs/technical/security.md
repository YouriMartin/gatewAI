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

`RateLimiter` is a **Bucket4j** token bucket, **one bucket per client id**, held in
an in-memory `ConcurrentHashMap`. Default: `60` requests/minute (greedy refill),
configurable via `gatewai.ratelimit.{enabled,requests-per-minute}`. Being
in-memory, the limit is **per instance**, not cluster-wide.

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
