# v4 — From portfolio to a tool people use

v1–v3 built a gateway whose decisions are traced, explained and calibrated. v4 changes
the objective: gatewAI should be **installed and kept by people who did not write it**.
The target user: a team that runs several providers — local models included — behind
one OpenAI-compatible endpoint, and has to explain what that endpoint does.

Five lots, in the order a stranger meets the product:

| Lot | The question a new user asks | Batches |
|---|---|---|
| A | Are the answers right on *my* traffic — conversations, system prompts, long prompts? | A.1–A.3 |
| B | Does my client work at all (content parts, tools, `/v1/models`)? Does it survive a provider outage? | B.1–B.6 |
| C | Can I trust the artifact I pull, and how long until I see it work? | C.1–C.4 |
| D | Can I try it on production traffic without risking a wrong answer? | D.1–D.4 |
| E | Can I rerun the numbers the articles quote? | E.1–E.2 |

Same conventions as v2 and v3: each batch is independently mergeable, has explicit
acceptance criteria, and updates the docs it invalidates in the same commit. Deviations
go to [`../decisions.md`](../decisions.md); structuring decisions are ADRs.

The minimum before any public announcement is **lot A, B.1–B.4 and C.1–C.3**. E.1 does
not depend on lot D and can move earlier if an article is waiting on it — but not before
C.3: its acceptance runs against the quickstart, and it reads `X-GatewAI-Cache` (A.2) and
pass-through results (B.3–B.4).

---

## Scope boundary to state up front

**Feature freeze on depth.** No new explainability, calibration or carbon-methodology
work until lot E ships. v3 lot D (measured energy for local models) is deferred, not
cancelled. The `Status` line of `AGENTS.md` moves to v4.

**Two non-negotiables of `AGENTS.md` are amended — deliberately, each by its own ADR, in
the batch that needs it.** Nothing else in that list changes.

| Rule today | Becomes | Batch / ADR |
|---|---|---|
| One processing chain (the advisor chain) | The chain stays the path for every request it can honour. Requests it cannot honour (tools, non-text parts, `response_format`, `n > 1`…) take an explicit, traced, accounted **pass-through** path | B.3 / ADR 0016 |
| No fallback provider | **Ordered failover inside a tier**: the registry entries of one tier, in declaration order. Still never across tiers, never to an undeclared model, never silent | B.5 / ADR 0017 (amends ADR 0003) |

**Credential rule (from B.3 on).** The `anthropic` egress accepts **Console API keys
only**. Anthropic reserves OAuth tokens from Free/Pro/Max accounts for Claude Code and
Claude.ai, and does not permit third-party developers to route requests through those
plan credentials on behalf of their users
(<https://code.claude.com/docs/en/legal-and-compliance>, read 2026-09-28). The provider
configuration therefore fails fast on a key shaped like a subscription token
(`sk-ant-oat…`, `sk-ant-ort…`), with a message saying why.

**Out of scope for v4, with the reason:**

- **Anthropic Messages ingress (`/v1/messages`) for Claude Code.** Anthropic documents
  third-party gateways for Claude Code but does not support routing it to non-Claude
  models (<https://code.claude.com/docs/en/llm-gateway>), and ships its own self-hosted
  gateway with SSO, per-group model access, failover and OTLP telemetry for that exact use
  case (<https://code.claude.com/docs/en/claude-apps-gateway>), both read 2026-09-28. Tier
  routing and a semantic cache do not apply to agent traffic, and the protocol moves with
  every Claude Code release. Revisit only on user demand.
- **OpenAI Responses API (`/v1/responses`) and `/v1/embeddings`.** Chat Completions is the
  common denominator of OpenAI-compatible servers (vLLM, Ollama, llama.cpp, LM Studio).
  v4 completes that surface before opening a second one.
- **Per-client budgets and spend alerts.** The most requested governance feature in this
  category — which is why it waits for a real user to say which shape they need. It
  depends on B.6.

---

# Lot A — Decisions that hold on real traffic

**Goal.** The semantic cache and the router decide from the **last user message only**:
`SemanticCacheAdvisor.extractUserText` and `RoutingAdvisor.extractUserText` both call
`Prompt.getUserMessage()`, which in Spring AI 2.0 returns the last `UserMessage`. The
system prompt, the conversation history, the pinned model and the OpenAI `user` field
never reach the cache key, and the embedding sees the first 128 tokens of that one
message. On real traffic this serves wrong answers deterministically, whatever the
threshold: two conversations under one API key that both end with "Give me an example in
Java" embed to the same vector, cosine 1.0, and the second receives the first one's
answer. In a support bot whose system prompt carries the customer's data, it is a leak
between end users.

**Why the evaluation never saw it.** The 300 cache pairs are single-turn, 41 characters
on average and 80 at most, with no system prompt. The calibration is sound; the
distribution it was measured on is not a gateway's.

## A.1 — Measure the conversation blind spot first

This batch measures. It does not change how the cache decides.

- New test-half dataset `src/test/resources/eval/conversation-test.jsonl`, EN/FR, about
  100 cases. Each case holds two requests as OpenAI `messages` arrays — `stored` (whose
  answer is in the cache) and `incoming` — plus `judgment` (YES = the stored answer may be
  served), `language` and `tags`. Tags and the labelling rule for each:
  - `follow-up-collision` — different histories, identical last turn ("continue", "give me
    an example in Java", "translate it", "why?"): **NO**.
  - `system-prompt-collision` — identical user turn, different system prompts (persona,
    output language, tenant context): **NO**.
  - `end-user-collision` — one system-prompt template carrying different per-user data
    (order status, account details): **NO**.
  - `template-prefix` — single-turn prompts longer than the embedding window that share
    their first 128 tokens (a RAG or summarisation template) and differ after: **NO**.
  - `same-context-paraphrase` — identical system prompt and history, paraphrased last
    turn: **YES**.
  - `first-turn-paraphrase` — identical system prompt, paraphrased first turn: **YES**.
- `ConversationCacheEvaluator` drives the **real** `SemanticCacheAdvisor` — store `stored`,
  then look up `incoming` — over an in-memory `VectorStore` that honours filter
  expressions (Spring AI's `SimpleVectorStore` does), with embeddings replayed from
  fixtures recorded by `EvalFixtureRecorderTest`. No re-implementation of the lookup. The
  advisor is package-private: it is built by a test-scope factory in its package, as
  `EvalClassifierFactory` does for the router. The cache fixtures today are pairwise
  similarities (`cache-similarities.json`), not vectors, so the recorder gains a vector
  fixture for the conversation texts. The advisor runs at the configured threshold with no
  calibration applied, like the existing fixed-threshold cache metrics.
- Two metrics per tag: `cross_context_hit_rate` (served on NO cases — lower is better) and
  `same_context_hit_rate` (served on YES cases). `baselines.json` gains a **ceiling** for
  the new lower-is-better metric (the `…Max` keys already work that way) and a floor for
  the other. This batch records today's values as they are.

**Acceptance**
- `./mvnw -DskipFrontend verify` is green; `target/eval/report.md` has a conversation
  section with both metrics per tag.
- `limitations.md` names the defect and quotes the measured figure ("owned, not hidden").
- The labelling rules sit in `src/test/resources/eval/README.md`, next to the existing two.

## A.2 — Scope the semantic cache by conversation context

**Decision to record (ADR 0014).** Similarity stays on the last user turn, but only
**inside an identical context**. The context becomes an exact filter; the embedding never
has to carry it.

- **Scope key.** SHA-256 over a canonical serialisation of: every system/developer
  message; every message before the last user turn (role + content); the model id when the
  request pins a registered model; the OpenAI `user` field when present (end-user
  isolation inside one API key); `stop` sequences when present. `OpenAiMapper` drops
  `user` and `stop` today — plumb them through. Stored as vector metadata `cache_scope`;
  the lookup filters on `client_id` (existing namespacing) **and** `cache_scope`.
  Temperature is deliberately not in the key; the ADR says why.
- **Bypass**, traced as `BYPASS` with a reason: a new `reason` column on `cache_decision`
  (new migration — no existing column fits, `conformal_status` is the shape of the
  prediction set). B.3 reuses it:
  - more than `gatewai.cache.max-history-messages` non-system messages (default 3, so the
    first follow-up stays cacheable inside an identical context);
  - a last user turn longer than the embedding window, counted with the embedding model's
    own tokenizer: semantic matching is off and the request is served by **exact match
    only** (a `turn_hash` of the full text joins the filter).
- **Store** only answers whose finish reason is `stop` and whose text is non-empty. The
  finish reason is provider-native today and reaches clients as-is (`STOP` from the OpenAI
  model, `end_turn` from Anthropic, `stop` from Ollama and the mock). One normaliser maps
  it to the OpenAI values (`stop`, `length`, `tool_calls`, `content_filter`) for both the
  cache and the response; without it, nothing from OpenAI or Anthropic would ever be
  stored.
- **Serve** a candidate only if its stored completion token count fits the incoming
  `max_tokens`, when one is set.
- Call and stream paths behave identically (`adviseCall`, `adviseStream`, `storeStreamed`).
- Legacy `vector_store` rows (no `cache_scope`) never match. `data-model.md` gives the
  optional cleanup statement.
- The cache decision row stores the scope hash (a hash, never text), so a hit can be traced
  to the context it was allowed in.
- Non-streaming responses carry `X-GatewAI-Cache: HIT|MISS|BYPASS` and `X-GatewAI-Model`.
  Streaming callers use `X-Request-Id` and the decision API.

**Acceptance**
- One unit test per A.1 category on `SemanticCacheAdvisor`, call **and** stream.
- A.1 harness: `cross_context_hit_rate` = 0 on the four NO tags; `same_context_hit_rate`
  printed next to its A.1 value — the delta is the price of correctness, shown, not hidden.
  The single-turn v2/v3 cache metrics do not move (a single-turn request with no system
  prompt has a single scope).
- Ceilings in `baselines.json` set to the new values.
- ADR 0014; `semantic-cache.md`, `limitations.md` (the A.1 finding becomes "fixed in A.2",
  with before/after), `data-model.md`, `api-reference.md`.

## A.3 — Conversation-sticky routing

**Decision to record (ADR 0015).** A conversation keeps its model unless a later turn
needs a higher tier. Two reasons: a short follow-up in a hard conversation must not drop
to the local tier, and changing model between turns forfeits the provider's prompt cache
on the whole history.

- **Fingerprint.** Chat Completions is stateless, but clients resend the history, so the
  opening of a conversation is stable: SHA-256 over (system messages, first user message,
  first assistant message). Defined for requests that contain at least one assistant
  message.
- **Record.** After a routed turn-1 response (not pinned), store
  `(fingerprint(system, first user message, answer), model_id, tier, created_at,
  last_seen_at)` in a new table (next free Flyway version). Streaming records on
  completion with the aggregated answer; a turn-1 cache hit records the cached answer and
  its model.
- **Use.** On a routed request with history: if a record exists, tier = max(recorded,
  classified) — never downgrade; an unchanged tier keeps the recorded **model**, not just
  the tier; a higher tier upgrades and updates the record. No record (conversation older
  than A.3, retention passed, turn 1 pinned) → tier = max(tier of the last user turn, tier
  of the first user turn). Each case is distinguishable in `routing_decision`.
- Pinned requests are untouched and never recorded.
- Retention `gatewai.routing.conversation-ttl` (default 24 h since `last_seen_at`); purge
  under `LeaderLock` with a new `LeaderTask`.
- Hashes only: no prompt or answer text in the table.

**Acceptance**
- Tests: a premium conversation's short follow-up stays on the same model; a local
  conversation upgrades on a hard follow-up and never comes back down; pinned requests are
  unaffected; streaming records on completion; the purge honours the TTL under the lock.
- `scripts/cluster-smoke.sh` gains one check: a conversation started on node A is sticky
  on node B.
- The single-turn routing metrics of the harness do not move.
- ADR 0015; `routing.md`, `clustering.md` (new shared state), `data-model.md`,
  `limitations.md` (clients that trim or rewrite history change the fingerprint;
  conversations with identical openings share a floor).

---

# Lot B — Speak the dialect real clients speak

**Goal.** "Any existing client SDK works by only changing the base_url" becomes true for
chat UIs, RAG applications and agents, not only for scripts that send plain text.

## B.1 — Accept the message shapes clients actually send

Today `ChatMessage(String role, String content)` cannot deserialise a `content` array, and
`SpringAiLlmClient` maps every unknown role to a user message.

- `content`: string **or** array of parts (`text`, `image_url`, `input_audio`, `file`;
  unknown types preserved). Text-only arrays are flattened (parts joined by `\n`, in order)
  and behave exactly like the string form: same scope key, same embedding input, same
  routing input.
- Roles: `developer` = `system`. `tool` messages, assistant `tool_calls` and legacy
  `function_call` are parsed and preserved.
- Fields: `max_completion_tokens` as an alias of `max_tokens`; `stop`, `top_p`,
  `presence_penalty`, `frequency_penalty` and `seed` forwarded to the egress where the
  provider supports them; `stream_options.include_usage` honoured (final usage chunk).
  Unknown fields are ignored, never rejected. `OpenAiMapper` drops all of these today
  (and `n`, which is accepted and silently ignored), and `RoutingAdvisor.reroutePrompt`
  rebuilds the options of every routed request keeping only temperature, max tokens and
  top-p: forwarded parameters must survive both.
- A request that needs pass-through — non-text parts, tools or tool messages,
  `response_format`, `n > 1`, `logprobs`, `logit_bias` — is **marked**. Until B.3 lands it
  gets a 400 in the OpenAI error envelope naming the feature, never a deserialisation
  error.
- JSON stays in `adapter/in/web`; the domain receives typed parts.

**Acceptance**
- Request fixtures under `src/test/resources/fixtures/openai-requests/`, each with a line
  in a README naming the client shape it mirrors and where that shape was taken from:
  OpenAI Python SDK (string content), Vercel AI SDK (multi-part text), a chat-UI request
  with an image, a LangChain tool-calling turn, `developer` role + `max_completion_tokens`.
  All deserialise.
- Text-part arrays and strings produce the same `LlmRequest` (test).
- Forwarded parameters reach each provider's options, on a pinned request and on a routed
  one (unit tests on the adapters and on the router). A table
  in `api-reference.md` says, per provider type, what is honoured, what goes through
  pass-through and what is ignored.
- A stream with `include_usage` ends with a usage chunk.

## B.2 — `GET /v1/models`

Chat UIs discover models by listing them; without the endpoint they show an empty picker.

- `GET /v1/models` returns the OpenAI list shape: first `auto` (owned by `gatewai`), then
  every registry entry (`id` = model-id, `owned_by` = provider instance name).
  `GET /v1/models/{id}` returns one entry, or a 404 in the OpenAI envelope. `created` is
  stable across restarts.
- `auto` means "let gatewAI route". Unregistered names keep being classified as today;
  registered ids pin.
- Same Bearer auth as `/v1/**`; not counted against the chat rate limit.

**Acceptance**
- Integration test on the shape.
- `getting-started.md` has a tested walkthrough for one chat UI (Open WebUI or LibreChat —
  the version tested is named): models listed, streaming chat works, `auto` routes, a
  pinned id pins.

## B.3 — Pass-through, non-streaming

**Decision to record first (ADR 0016, amends ADR 0002 and the "one processing chain"
rule).** The advisor chain stays the path for everything it can honour. What it cannot
honour is forwarded as-is — and still traced, accounted and rate-limited.

- **Trigger:** the B.1 markers.
- **Model:** a pinned registered id → that entry; otherwise
  `gatewai.passthrough.default-model` (a registry id; default: the first entry of the
  highest tier). Never classified — a tool loop on the local tier is the failure mode this
  avoids.
- **Forwarding:** the original JSON body with only `model` rewritten, to the instance's
  OpenAI-format endpoint: `openai` → its base URL or the public API;
  `openai-compatible` → `{base-url}/chat/completions`; `ollama` →
  `{base-url}/v1/chat/completions`; `anthropic` → Anthropic's OpenAI SDK compatibility
  endpoint. Anthropic presents that layer as a way to test Claude through the OpenAI SDK,
  and it does not support prompt caching
  (<https://platform.claude.com/docs/en/api/openai-sdk>, read 2026-09-28): both go into
  `limitations.md`. A native OpenAI→Messages translation is a later lot, on demand.
- **Mock upstream:** the `mock` profile has no HTTP egress (`MockEchoChatModel` replaces
  the chat models), so it gains a built-in OpenAI-format echo for pass-through, including a
  scripted tool call. C.3's mock preset, C.4's demo traffic and E.1–E.2 depend on it.
- **Response:** status and body relayed; upstream errors mapped to the OpenAI envelope
  under the existing no-leak rule.
- **Accounting:** `usage` parsed from the upstream response → `request_log` and green
  accounting through the same code as the chain; `cache_decision` = `BYPASS` with a
  `PASSTHROUGH_<feature>` reason (the A.2 column); a `routing_decision` row with a
  pass-through reason. Hashes only.
- **Credential guard:** an `anthropic` instance whose key starts with `sk-ant-oat` or
  `sk-ant-ort` fails the context at startup (see the scope boundary).
- **Layout:** an inbound use case in `domain.port.in`, an outbound port implemented in
  `infrastructure/llm`; JSON manipulation (model rewrite, usage extraction) stays in the
  adapters. `ArchitectureTest` stays green.

**Acceptance**
- Against a stub upstream (JDK `HttpServer` or `MockRestServiceServer`; a new test
  dependency only with a stated reason): a full tool-calling round trip (assistant
  `tool_calls` → `tool` message → final answer) using the B.1 fixture shapes; a
  `response_format` json_schema request; an image part. Each lands in `request_log` with
  its usage; the cache is never consulted, and the trace says why.
- Upstream 4xx/5xx → OpenAI envelope without the upstream body.
- Startup fails on an `sk-ant-oat…` key with the explanatory message.
- ADR 0016; the `AGENTS.md` non-negotiables amended in the same commit; `architecture.md`
  (diagram), `api-reference.md`, `security.md`, `limitations.md`.

## B.4 — Pass-through, streaming

- SSE relayed chunk by chunk, never buffering the whole answer; `[DONE]` preserved.
- `stream_options.include_usage` injected upstream; the usage chunk is dropped before the
  client when the client did not ask for it.
- Accounting on completion. A client disconnect cancels the upstream call and is recorded.

**Acceptance**
- Stub-upstream tests: a streamed tool-calling round trip and a streamed text answer; a
  delayed-chunk test proving chunks reach the client as they arrive.
- Manual check recorded in `getting-started.md`: one OpenAI-compatible coding agent (the
  maintainer's pick, version named) completes a file-editing task through gatewAI — once
  on a local Ollama model with tool support, once on a cloud provider.

## B.5 — Ordered failover inside a tier

**Decision to record first (ADR 0017, amends ADR 0003 and the "no fallback provider"
rule).** Today only the first registry entry of a tier is used. The entries of a tier, in
declaration order, become its failover chain. The registry binds into a `LinkedHashMap`,
so that order is file order; the ADR says how entries from several property sources (a
profile file, environment variables) are ordered, or adds an explicit priority.

- **Triggers:** connection failure, timeout, HTTP 429, 5xx (including Anthropic's 529) —
  before the first byte reaches the client. Never on other 4xx.
- **Timeouts:** explicit connect/read timeouts per provider instance. Today
  `openai`/`openai-compatible` instances get a hard-coded 60 s timeout and 3 SDK retries
  (`EgressProviderConfiguration`); Anthropic and Ollama use library defaults.
- **Retries:** establish how Spring AI 2.0's retry applies to the chat models
  `EgressProviderConfiguration` builds (in 1.x the default retried many times with
  exponential backoff). Gateway failover becomes the only retry layer, or Spring AI's is
  capped — the ADR says which.
- **Cooldown:** after N consecutive failures an instance is skipped for a cooldown, then
  probed again. Per node and in memory — acceptable, and stated in `clustering.md`.
- **Scope:** routed requests and pass-through default-model requests. Pinned requests do
  not fail over unless `gatewai.failover.pinned=true` — they asked for that model.
- **Stickiness (A.3):** the conversation follows the model that actually answered.
- Both paths: the chain (`DelegatingChatModel`) and pass-through.
- Traced (`failover_from`, cause) and metered
  (`gatewai_failover_total{tier,from,to,cause}`), with a panel in the committed Grafana
  dashboard.

**Acceptance**
- Tests per trigger, on both paths, streaming and not; no failover after the first byte;
  the cooldown/probe cycle; the pinned default.
- ADR 0017; `AGENTS.md` updated; `routing.md`, `observability.md`, `limitations.md`.

## B.6 — Split pricing and an explicit currency

The energy model is already split by phase (v3 C.4); the price is still a blended
average, which overstates input-heavy traffic (RAG, agents) — and therefore the savings
the dashboard prints.

- Registry: `pricing.input-per-1m`, `pricing.output-per-1m`,
  `pricing.cached-input-per-1m` (per million tokens, the unit providers publish), plus
  `gatewai.billing.currency` (ISO 4217). `cost-per-1k-tokens` keeps working for one
  release, labelled `BLENDED`, with a startup warning.
- Cached input tokens are read from provider usage when reported (OpenAI
  `prompt_tokens_details.cached_tokens`, Anthropic cache-read tokens); otherwise priced at
  the input rate and labelled so.
- `request_log` gains the cached-token count, a pricing basis (`SPLIT` | `BLENDED` | `NONE`)
  and the currency (new migration). Pre-v4 rows stay `BLENDED`, never back-dated. The
  avoided-cost baseline (ADR 0006) uses split prices.
- The currency is in the names today: `request_log.cost_eur` and `cost_avoided_eur`, the
  report API's `total_cost_eur` / `total_cost_avoided_eur`, the CSV/PDF columns and
  `api.ts`. The batch either renames them (migration, with the old API fields kept for one
  release like `cost-per-1k-tokens`) or keeps them and documents that they hold the
  row's currency; `decisions.md` records which. Pre-v4 rows are `EUR`.
- No price recalled from memory: every example price in `application.properties` carries
  its source URL and read-date, or stays a commented placeholder marked TODO.

**Acceptance**
- Mirror-image requests (10k in / 50 out vs 50 in / 10k out) differ by the price ratio;
  cached tokens are priced at the cached rate when reported.
- Every API, CSV, PDF and dashboard cost figure shows its currency and pricing basis.
- `green-accounting.md` (cost section), `api-reference.md`, `data-model.md`.

---

# Lot C — Trust the artifact, then see it work

**Goal.** Today, trying gatewAI means cloning, building Maven and Node inside Docker,
fetching the ONNX model, then pulling ~3 GB of Ollama models — and the default all-local
setup shows 0 and "excluded from scope" for cost and carbon. After lot C: pull a
verifiable image, point it at a model you already have, see a cache hit.

## C.1 — A release people can pull and verify

- `.github/workflows/release.yml` on tag `v*`: a multi-arch image (`linux/amd64`,
  `linux/arm64`) from the existing `Dockerfile`, pushed to
  `ghcr.io/yourimartin/gatewai:<version>` and `:latest`; SBOM and build-provenance
  attestation; a GitHub release with the changelog excerpt.
- Every `uses:` in every workflow pinned to a full commit SHA, the version in a trailing
  comment, and a CI check that fails on any tag-pinned action. Dependabot for
  `github-actions`, `maven`, `npm` and `docker`. Rationale, for the docs: in March 2026 the
  TeamPCP campaign compromised CI tooling, and an unpinned Trivy dependency in LiteLLM's
  pipeline leaked its PyPI publishing credentials
  (<https://docs.litellm.ai/blog/security-update-march-2026>, read 2026-09-28).
- Versioning: no `0.0.1-SNAPSHOT` in a release — the version comes from the tag
  (CI-friendly Maven versions, or `versions:set` in the workflow).
- The Hugging Face URLs point at a pinned revision instead of `resolve/main`. The SHA-256
  check stays; pinning only avoids a build that breaks when upstream moves.
- `CHANGELOG.md` (Keep a Changelog), `SECURITY.md` (how to report, supported versions), a
  root `CONTRIBUTING.md` pointing at `docs/developpment/contributing.md`, issue templates (a
  bug report asks for the version, the client, the provider type, a config excerpt without
  secrets, and the `X-Request-Id`).
- Pushing the tag is the maintainer's step: `git push` is denied to agents.

**Acceptance**
- A test tag produces a pullable multi-arch image that boots with the `mock` profile and
  answers one request on **both** architectures (arm64 under QEMU in the workflow: ONNX
  Runtime and the DJL tokenizer load per-architecture native libraries); the
  attestation verifies with `gh attestation verify`.
- No tag-pinned action is left, and CI enforces it.

## C.2 — One name, one story, docs that match the code

- "Green AI Proxy" / `greenaiproxy` → gatewAI in every user-facing place (dashboard title,
  HTML title, exports, docs). Existing Docker volumes must keep working: a default database
  name changes only where no existing install can depend on it, and the upgrade note says
  so.
- `HELP.md` deleted. Doc drift fixed — for example `tech-stack.md` still says 768
  dimensions, and `functional-choices.md` still says the router embeds with "the same
  Ollama model as the cache". Grep for `nomic`, `768`, and `Ollama` near `embed`.
- The positioning line and "Why gatewAI?" rewritten; `AGENTS.md` ("a Java/Spring portfolio
  project") and `limitations.md` ("portfolio-grade project") follow. Direction: the LLM
  gateway you can audit — every cache hit and routing decision traced, explained and
  replayable; Spring Boot; PostgreSQL as the only dependency. The maintainer picks the
  final wording.
- `docs/README.md` opens with a short user path: quickstart, configuration reference,
  client compatibility, troubleshooting. A new `troubleshooting.md` lists each error a user
  can get (`unknown_model`, the B.1 unsupported-feature 400, 429, upstream errors), derived
  from the exception handlers, and how to find a request by `X-Request-Id`.

**Acceptance**
- `grep -riE "green ai proxy|greenaiproxy"` matches only historical logs (`progress.md`,
  `decisions.md`, roadmaps, `plan-action-*.md`) and the default database name wherever it
  stays so that existing volumes keep working.
- The user path and `troubleshooting.md` exist and are linked from the README.

## C.3 — Quickstart on the published image

- `docker-compose.quickstart.yml`: pgvector + the published gateway image — no build, no
  bundled Ollama. Three documented and tested egress presets: an Ollama already running on
  the host; any OpenAI-compatible endpoint + key; `mock` (zero dependency). Presets are
  selected by environment variables, never by editing `application.properties`.
- The three local model ids become env-overridable. Changing the defaults themselves is a
  maintainer decision, not part of this batch.
- The README top is rewritten: the positioning line (from C.2), a three-command quickstart,
  "what you should see" (the same curl twice: `X-GatewAI-Cache: MISS`, then `HIT`), a client
  compatibility table (client + version tested), the link to `limitations.md`.
- Time from `docker compose up` (image already pulled) to the first successful response,
  measured on a clean machine and written in the README with the hardware.

**Acceptance**
- A CI job starts the quickstart compose with the `mock` preset on the locally built image
  and runs the README commands, asserting MISS then HIT — so the README cannot silently rot.

## C.4 — Demo data and screenshots that say where they come from

- `scripts/demo-traffic.sh` replays a small bundled synthetic set (~30 requests: repeats
  and paraphrases, a multi-turn conversation, a pass-through tool call) against a running
  gateway.
- A Playwright script (a devDependency of the frontend — a test tool, not a UI framework)
  captures the dashboard overview and the "why this decision" panel into `docs/assets/`.
- Real traffic when the maintainer provides an endpoint and key (the caption names the
  provider), otherwise the mock egress — and every caption says which, with the date.

**Acceptance**
- One command regenerates the screenshots; the README shows the explanation panel; no
  figure in an image that the documented command cannot reproduce.

---

# Lot D — Shadow mode: try it on real traffic without risk

**Goal.** Nobody turns on a semantic cache in production on day one. Shadow mode lets a
team put gatewAI in front of real traffic, change nothing its clients receive, and read
what the cache and the router *would* have done — and how often the cache *would* have
been wrong. It also closes the loop `conformal-calibration.md` leaves open: labels that
come from production traffic, so the calibration is exchangeable with it. Depends on B.3,
B.4 and B.6.

## D.1 — Shadow mode per client

- `api_client` gains a mode: `ENFORCE` (default, today's behaviour) | `SHADOW` (new
  migration); admin API and dashboard toggle; a mode change is logged with the admin
  client id.
- A `SHADOW` request is served by the pass-through path to the model it names (a registry
  id, or `auto` → `gatewai.passthrough.default-model`; an unregistered name gets the
  existing `unknown_model` 400 — shadow never guesses). The client receives the upstream
  answer unchanged.
- Off the critical path (virtual threads, bounded concurrency), the gateway computes the
  cache verdict (would-hit / miss / bypass, similarity, scope) and the routing verdict (tier
  and model, stickiness included), and records them with `mode = SHADOW`. The cache stores
  the real answer under the A.2 rules, so it warms up as it would in production. Under
  overload, shadow work is dropped and counted, never queued without limit.
- Metric `gatewai_shadow_decisions_total{outcome}`.

**Acceptance**
- Stub-upstream integration test: a shadow client receives the upstream bytes unchanged
  even when the cache holds a would-hit; decisions are recorded as `SHADOW`; an `ENFORCE`
  client is unaffected.
- The latency shadow adds to the served request is measured and stated (target: nothing
  beyond pass-through).

## D.2 — Would the cached answer have been right?

- For every shadow would-hit, compare the cached answer with the answer the client
  actually received:
  - always: the similarity of the two answers under the in-process embedding model (the
    128-token window stated);
  - on a sample: an LLM judge (a registry model, sample rate configurable, off by default)
    with a versioned rubric returning YES / NO / UNSURE. Judge calls are accounted like any
    request, under a system client.
- Stored per would-hit: answer similarity, verdict, judge model, rubric version. No new
  plaintext — the real answer is compared in memory and discarded.
- Estimated wrong-hit rate per client, with a Wilson interval and its sample size, labelled
  "judged by <model>, not by a human".

**Acceptance**
- Tests on the interval (against known values), the sampling, and rubric parsing (UNSURE
  excluded and counted); a test asserting that no new column or table holds answer text.
- The dashboard and the API show rate, interval, sample size and the judge label together.

## D.3 — Calibrate on production-labelled cases

**Decision to record (ADR 0018).**

- `POST /v1/admin/calibration` accepts `source=shadow` (judge-labelled would-hits, for one
  client or all) next to the shipped hand-labelled set. The conformal machinery is
  unchanged; the guarantee changes subject — "at most α of the pairs the judge rejected are
  served" — and the docs say so.
- A minimum sample is required before a shadow calibration (configurable; for example 200
  pairs with at least 20 NO), otherwise a 409 that says why. Staleness rules unchanged.
- `conformal_calibration` holds one row per target (primary key `target`), so a
  calibration fitted on one client's labels applies to every client unless the schema
  changes. The ADR decides which. It also says what the guarantee loses because judge
  labels exist only for would-hits, that is, above the threshold in force.

**Acceptance**
- Tests on source selection and the minimum-sample guard; the calibration row stores its
  source. `conformal-calibration.md` and `limitations.md` updated.

## D.4 — The shadow report

- `GET /v1/reports/shadow?from=&to=&client=`, with CSV/PDF exports and a dashboard panel:
  requests observed, would-hit rate, estimated wrong-hit rate (interval + judge label),
  what the client paid vs what it would have paid (split pricing, B.6), CO2 with its scope,
  and the routing mix gatewAI would have applied.

**Acceptance**
- Every figure re-derives from stored rows; exports state basis and scope on their face
  (never a bare zero); a golden test on a fixed dataset.

---

# Lot E — Evidence people can rerun

## E.1 — `gatewai-replay` and a public dataset

- A small CLI under `tools/replay/`, a separate Maven project with its own `pom.xml` (not a
  module of the gateway build — the gateway jar does not change). It replays a JSONL of
  OpenAI chat requests against **any** OpenAI-compatible base URL: single-turn requests,
  multi-turn conversations sent turn by turn with the answers actually received,
  RAG-shaped prompts, tool-calling requests. It has a concurrency option, sets
  `X-Request-Id`, records status, latency, `X-GatewAI-Cache` and model, and — given an
  admin key — pulls each decision by id.
- Output: a markdown report (hit rate per category, cross-context hits, latency p50/p95,
  pass-through success, cost and CO2 from the report API) plus the raw CSV, headed by
  commit, configuration, hardware and date.
- `datasets/replay-v1.jsonl`: ~300 synthetic requests, EN/FR, CC0, no real user data, the
  generation method documented in `datasets/README.md`, categories aligned with A.1's tags.

**Acceptance**
- A run against the quickstart (`mock`) completes and produces the report; a run against
  a plain upstream without gatewAI works too (the baseline).

## E.2 — Overhead benchmark, published with its conditions

- The latency gatewAI adds to a direct call, against the `mock` egress: miss path, hit
  path, pass-through path, at three concurrency levels, after a stated warm-up;
  p50/p95/p99 and throughput.
- `docs/technical/performance.md`: hardware, JVM flags, commit, the command, and what the
  numbers do not measure.

**Acceptance**
- One command reproduces it; the documented numbers are copied from the committed run
  output, which is part of the change.

---

## Not scheduled — on user demand

Budgets and spend alerts per client (after B.6) · `/v1/responses` and `/v1/embeddings` ·
a native OpenAI→Anthropic Messages translation (keeps Anthropic prompt caching on
pass-through) · dashboard session auth / OIDC instead of a key in local storage · v3 lot D
(measured energy for local models) · a `/v1/messages` ingress (see the scope boundary).
