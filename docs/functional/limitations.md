# Limitations

gatewAI is a portfolio-grade project with deliberate scope. These limits are
**owned, not hidden** — read them before relying on the numbers or deploying for
real traffic. Where relevant, the technical rationale is linked.

## Carbon figures are directional, not audited

- **Per-model energy coefficients are placeholders.** The kWh-per-token values in
  the model registry are rough estimates, not measured. Absolute energy/CO2
  numbers should be read as **directional**, not exact.
- **Average vs marginal intensity.** Real-time intensity (when enabled) uses the
  grid **average**, while an *additional* load is actually served by the
  **marginal** plant. The greenest-zone *ranking* is reliable; the absolute gCO2
  is not.
- **Geography is accounting, not physical.** Carbon-aware "geo" routing chooses an
  accounting zone; it does **not** physically execute the request in another
  region. The benefit is reportable, not a real relocation. (Temporal deferral is
  real.)

Full discussion:
[`../technical/carbon-intensity-reliability.md`](../technical/carbon-intensity-reliability.md).
For audited CSRD claims you would need measured energy factors, marginal
intensity, real multi-region execution and a documented methodology.

## Egress providers: local-first by default; any mix by configuration

- Out of the box the egress is **100% local**: the three routing tiers map to
  three Qwen sizes (`qwen2.5` 0.5b/1.5b/3b) on the bundled Ollama, pulled at
  startup — the gateway needs **zero API keys**. (See
  [`../technical/routing.md`](../technical/routing.md) for the
  `DelegatingChatModel` and provider instances.)
- **Any provider mix is opt-in configuration**: declare instances under
  `gatewai.providers.<name>` (`anthropic`, `openai`, `openai-compatible` — vLLM,
  LM Studio, llama.cpp, OpenRouter, DeepSeek… — or more `ollama` servers) and
  point registry entries at them. Only instances referenced by the registry are
  built, and startup fails fast on missing keys/endpoints.
- The default local models are small (chosen for speed and pull size); swap in
  larger local models or a cloud tier for frontier quality — see the commented
  examples in `application.properties`.

## Many OpenAI request fields are accepted but ignored

The ingress DTO accepts the common OpenAI fields (`top_p`, `stream`, `n`, `stop`,
`presence_penalty`, `frequency_penalty`, `user`); `model`, `messages`,
`temperature`, `max_tokens` and **`stream`** are honored, the rest are not. In
particular:

- **Streaming is supported** (Phase 7.5): `stream: true` returns Server-Sent
  Events (`chat.completion.chunk` deltas + `[DONE]`), including a synthetic stream
  on a cache hit.
- `n`, `stop`, `top_p`, the penalties and `user` are not applied.
- No tool/function calling, no `response_format`/structured outputs on the public
  chat endpoint, no images/audio. Only `/v1/chat/completions` is implemented from
  the OpenAI surface (no `/v1/embeddings`, `/v1/models`, etc.).

## Semantic cache trade-offs

- A high-enough similarity can return a **stored answer for a prompt that only
  looks similar**, which may be wrong or stale for the new intent. Tune the
  threshold for your tolerance.
- The default **TTL is 0 (no expiry)**: cached answers are reused indefinitely
  until evicted, so time-sensitive or fast-changing content can go stale. Set a
  TTL for such workloads.
- Cache quality is bounded by the embedding model — since v3 lot A an
  in-process ONNX model (`paraphrase-multilingual-MiniLM-L12-v2`, 384 dim, EN/FR).
- **Measured (v3 batch A.4)**: on 300 hand-labelled `(query, cached entry)`
  pairs, the shipped 0.92 threshold wrongly serves **14 %** of the answers it
  should have refused and wrongly refuses **61 %** of those it could have served.
  (On the previous embedding model the same constant gave 16 % / 46 % — the
  threshold did not move, the similarity scale under it did.) No
  single threshold makes both small — the two distributions overlap. A residual
  ~4 % of false positives is irreducible by **any** threshold: they are questions
  whose answer changed since it was cached, a freshness problem a TTL fixes and
  similarity cannot.
- **Calibrated (v2 batch 3, re-fitted in v3 A.4)**, the threshold is fitted
  rather than guessed: **0.9526** at α = 0.10. On the current model it holds the
  wrong-answer rate at 14 % while the hit rate falls from 25 % to **13 %**, so
  fewer wrong answers is bought with more model calls — and on this model the
  bill is higher than it was. The
  guarantee is **marginal, not per-request**: "at most α of non-servable pairs
  are served" describes the population, never your request. It also assumes the
  calibration cases are exchangeable with production traffic; the shipped labels
  are deliberately adversarial, so the measured rates read as a worst case and
  the promise transfers only as far as that resemblance holds. See
  [`evaluation.md`](../technical/evaluation.md) and
  [`conformal-calibration.md`](../technical/conformal-calibration.md).
- Not suitable as-is for strictly personalized or real-time answers unless you
  raise the threshold / set a TTL / rely on per-client namespacing.

## Complexity classifier is fallible

- The default **embedding** classifier is only as good as its route examples
  and its embedding model: `paraphrase-multilingual-MiniLM-L12-v2` covers
  EN/FR, and other languages need their own examples to match reliably.
  Requests unlike any example fall back to the heuristic. Its tokenizer
  truncates at **128 tokens**, so a long prompt is embedded from its opening
  — deliberate for routing (length is already a deterministic signal), a real
  limit for caching long prompts.
- The **heuristic** classifier uses length and a finite (bilingual EN/FR)
  keyword/code-block list. It can misroute: a short but genuinely hard question may
  go `LOCAL`, and a long but trivial one may go `CLOUD_PREMIUM`.
- The **LLM** strategy is more nuanced but adds a small classification call and can
  still be wrong.
- The router optimizes for cost tier, not for guaranteed answer quality on every
  request.
- **Measured (v3 batch A.4)**, on 300 hand-labelled prompts: the default
  embedding strategy picks the labelled tier **81 %** of the time at the shipped
  0.25 threshold and **82 %** calibrated, against the heuristic's **34 %** on the
  same set. Errors split 12 over-routed / 7 under-routed per 100.
- **The threshold belongs to the embedding model — shipping the wrong one is
  expensive.** On the previous model (`nomic-embed-text`) the shipped 0.60 gave
  62 %, mostly by *under*-routing: prompts below the bar fall back to the
  heuristic, which sends short text to `LOCAL`. Calibration lifted that to 83 %.
  The in-process model has a different scale — at 0.60 it would hand 88 of 100
  prompts to the heuristic — which is why the default moved to 0.25.
- **A calibrated threshold carries a stated guarantee, not a per-request
  promise**: coverage is marginal over the distribution (measured 91 % against a
  90 % target), so it says nothing about *your* prompt. The carbon saving moves
  with accuracy too — 38 % here, printed next to the 7 under-routed requests per
  100 that partly produce it. See
  [`evaluation.md`](../technical/evaluation.md) and
  [`conformal-calibration.md`](../technical/conformal-calibration.md).
- **The cascade (v2 batch 4) is opt-in, and its benefit is not measured.** It
  escalates 23 % of requests to the classifier model and those requests hold
  61 % of the routing errors, so the gate targets the right ones. Whether the
  model *fixes* them cannot be measured hermetically: the harness has no model
  server, and with level 3 stubbed by the heuristic the cascade scores 6 points
  below the routes alone. Turn it on only where the classifier model is better
  than the heuristic on hard prompts, and watch
  `gatewai_cascade_escalations_total`.
- **A client can bypass routing.** Naming a registered model id pins it (v2
  batch 4), which is the point — the gateway is also a plain proxy — but it means
  the green router only governs traffic that does not pin. Set
  `gatewai.classifier.client-pinning=false` to make routing mandatory.

## Attribution is an approximation, and reads more precise than it is

Since v2 batch 7 the gateway can say which parts of a prompt carried its routing
decision, by removing each segment and measuring what the similarity loses
([`attribution.md`](../technical/attribution.md)).

- **It assumes contributions are roughly additive**, which is strictly false for
  a contextual encoder: removing "not" changes what every other word means. The
  method is a useful indication of *which words mattered*, not a decomposition
  of the decision. The `share` column looks like a percentage — it is a
  normalization of positive contributions, nothing more.
- **It explains the match, not the ranking.** Why the winning route beat the
  others is a different question.
- **It costs n + 1 local embedding calls**, capped at 20 segments and cached, and
  it runs only when someone asks. It is still the heaviest thing v2 can ask of
  the embedding model.
- **Sentence boundaries are imperfect.** The JDK's `BreakIterator` breaks after
  abbreviations ("Ask Dr. | Martin…"), which costs one extra segment. Accepted
  rather than patched per language.

## Counterfactuals explain the ranking, not the final tier

Since v2 batch 8 the gateway can also say where a request would have gone
instead, and by how little it missed
([`attribution.md`](../technical/attribution.md)).

- **They describe which route came closest**, which is the whole answer only when
  the router took the routes' word for it. A similarity below threshold, a
  fallback, a cascade escalation or a client-pinned model id can all send the
  request somewhere the ranking does not predict — the stored decision is what
  says what actually happened.
- **A gap is not a probability.** It is a cosine difference against the routes
  configured *now*: it says how close the ranking was, not how likely the other
  outcome was.
- **They move when the routes do.** Nothing is cached, so an answer always
  reflects the current configuration — and two answers taken either side of a
  route edit are not comparable.

## A past decision replays; its analysis does not

`POST /v1/admin/decisions/explain` returns the stored decision for a past
request in full, but its attribution and counterfactuals come back as
`PROMPT_UNAVAILABLE`
([`decision-tracing.md`](../technical/decision-tracing.md)).

- **The decision trace stores no plaintext prompt**, only a SHA-256 hash, and
  both analyses have to re-embed the text. Paste the prompt to analyse it against
  the current rules — which is a different question, and the response says so.
- **The semantic cache is the exception**, and it is one worth knowing: it stores
  the question and the answer, because that is what it replays. It is namespaced
  per client and can be given a TTL; a deployment that must retain no prompt text
  turns the cache off, not the tracing (see the compliance note in
  [`decision-tracing.md`](../technical/decision-tracing.md)).
- **The trace is not an audit log.** Rows are purged on a retention timer
  (`gatewai.decisions.retention-days`, 90 days) and carry no actor. A 404 on a
  request you know happened usually means retention passed, or recording is off.
- **Traceability is not certification.** The trace supports the transparency
  angle of the EU AI Act for a component that routes and caches; gatewAI is
  infrastructure and claims no compliance of its own. Article 50's obligations
  apply from 2 August 2026 and bind the **provider or deployer of the AI
  system** — sources and scope in the compliance note.

## Running more than one replica

**Supported since v3 lot B**, and demonstrated rather than asserted:
`docker-compose.cluster.yml` starts two replicas behind an nginx balancer on one
PostgreSQL, and `scripts/cluster-smoke.sh` exercises every mechanism against it.
The last run: config propagated node→node in ~4 s, 12 deferred jobs split 7/5
across the nodes with each executed exactly once, a 60/min quota held cluster-wide
(62 allowed / 8 refused, the 2 extra being the greedy refill during the burst),
both nodes skipping the gated purge while its lock was held, one admin client
seeded by two nodes booting together, and each node tagging its metrics with its
own `instance`.

**One setting a cluster must change.** Rate-limit buckets are in the heap by
default, so the 60 req/min limit is per process and N replicas allow N × the
quota. Set `gatewai.ratelimit.store=postgres` (v3 lot B.3) — it costs ~3.8 ms p95
per limited request, which is why a single node, where the heap is correct and
free, keeps the cheap default.

**What stays node-local, and why that is fine:**

- the **attribution LRU cache** — keyed on prompt hash + embedding model + config
  version, so a miss costs a recomputation, never a wrong answer;
- the **conformal snapshot** — a read-through cache with a 60 s TTL, so nodes
  converge within a minute of a recalibration;
- the **semantic route index** — derived from the shared configuration and rebuilt
  when it changes.

**What to know before scaling out:**

- `gatewai_routing_config_changes_total` counts the edit **each node observed**, so
  one edit reads as N summed across replicas. Read it per `instance`, or take the
  `max` — the provisioned drift panel does.
- A deferred job whose execution outlives `gatewai.dispatch.job-lease-ms` (5 min)
  can be requeued and run twice. Concurrent claims are exactly-once; a lease
  expiry is at-least-once, deliberately.
- Every node must reach the same PostgreSQL, and nothing else. There is no Redis,
  no ZooKeeper, no leader election to operate: coordination is `SKIP LOCKED`
  claims and advisory locks in the database you already run.

Full state-by-state inventory:
[`../technical/clustering.md`](../technical/clustering.md).

## Deferred jobs keep prompts, with no retention

The carbon-aware queue stores the request in clear text — it has to, since the job
runs long after the client is gone — and **nothing purges it**: completed jobs stay
in `deferred_job` until deleted by hand. The endpoint is opt-in and disabled by
default (`gatewai.dispatch.enabled=false`); the vector cache is the only other
place prompt text is persisted, and that one has a TTL.

## Carbon-aware dispatch is off by default

The async endpoint and the greenest-zone worker are **disabled by default**
(`gatewai.dispatch.enabled=false`). Submitting async jobs without enabling the
worker will queue them without execution.

## Security scope

- Authentication is **API-key only** (Bearer). There is no OAuth, SSO or session
  login.
- The dashboard keeps the key **in the browser** (local storage) — acceptable for
  a self-hosted internal tool, not for a public multi-user deployment.
- `/actuator/health`, `/actuator/info` and `/actuator/prometheus` are **public** to
  ease scraping; restrict them by network/firewall in production.
- Real-time carbon (ElectricityMaps) and any provider keys require secrets you
  supply via environment variables.

## Build / validation gaps

- The **GraalVM native image** is "native-ready" but the full native compilation is
  not validated in this environment (see
  [`../technical/native.md`](../technical/native.md)); PDF export in particular may
  need extra native resource hints.

## Summary

gatewAI convincingly demonstrates the *architecture and direction* of a green LLM
gateway. The **savings logic, caching, routing and reporting are real**, and the
runtime **runs as a cluster** (v3 lot B — one setting to change, one compose file
to prove it); the **absolute carbon numbers are placeholders** and the **provider
matrix is minimal by default**. Plan accordingly.
