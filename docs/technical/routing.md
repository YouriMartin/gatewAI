# Smart routing

The router sends each request to the cheapest model tier that can handle it.
Sources: `infrastructure/llm/RoutingAdvisor`, the complexity classifiers, the model
registry, and the hot-config plumbing.

## Tiers and the model registry

Three tiers (`domain/model/ModelTier`): `LOCAL`, `CLOUD_ENTRY`, `CLOUD_PREMIUM`.

The **model registry** maps configuration to `ModelDefinition`s
(`PropertiesModelRegistry` over `ModelRegistryProperties`, prefix
`gatewai.models.registry`). Each entry has: provider, model id, €/1k tokens, energy
intensity (kWh/1k tokens), and tier. Default registry:

| Key | Provider | Model id | Tier | €/1k | kWh/1k |
|---|---|---|---|---|---|
| `claude-opus` | anthropic | claude-opus-4-8 | `CLOUD_PREMIUM` | 0.015 | 0.005 |
| `claude-haiku` | anthropic | claude-haiku-4-5 | `CLOUD_ENTRY` | 0.003 | 0.002 |
| `ollama-local` | ollama | qwen2.5:0.5b | `LOCAL` | 0.0 | 0.001 |

> The energy intensities are **placeholders** (see
> [`green-accounting.md`](green-accounting.md)). The model ids are configurable.

`ModelRegistry` (out port) offers `findByTier`, `findByModelId`, `findByKey`,
`allModels`.

## Classification

`ComplexityClassifier` (out port) has three implementations, selected per call
by `gatewai.classifier.strategy` through the `@Primary`
`DelegatingComplexityClassifier` (the seam where a future cascade mode will
live, see [Future work](#future-work-cascade-routing)):

The port returns a `ClassificationOutcome` — the tier **and** a
`ClassificationJustification` (v2 batch 1). Every strategy already computed its
reason and threw it away; capturing it costs nothing on the nominal path and is
what makes the decision explainable. The justification is a sealed hierarchy, so
a new strategy cannot be added without every reader saying what it renders:

| Variant | Carries |
|---|---|
| `Heuristic` | which rule fired (`CODE_FENCE`, `PREMIUM_KEYWORD`, `PREMIUM_LENGTH`, …), the matched keyword or the observed length vs its threshold |
| `Embedding` | every route ranked, each with its closest example, plus `topScore`, `margin` (top1 − top2) and the threshold in force |
| `Llm` | the model's own `reasoning` and the classifier model id |
| `Fallback` | the strategy that stepped aside, the cause, what actually decided, and the evidence it had gathered first |
| `FailSafe` | no strategy decided — premium was chosen defensively |

`Fallback` is what makes a **degraded** decision distinguishable from a nominal
one: the same tier reached by the heuristic means something quite different when
it was reached because Ollama was unreachable. Causes are
`NO_ROUTES_CONFIGURED`, `BELOW_THRESHOLD`, `EMBEDDING_ERROR`,
`NO_TIER_RETURNED` and `LLM_ERROR`. On a below-threshold hand-over the route
scores ride along as `evidence`, so "the best route only reached 0.41 against a
0.60 bar" stays visible — the quantity batch 3 will calibrate.

### Embedding routes (default) — `EmbeddingComplexityClassifier`

Semantic routing in the style of
[aurelio-labs/semantic-router](https://github.com/aurelio-labs/semantic-router)
and the vLLM Semantic Router. Each **route** is a named intent bucket mapped to
a tier and described by **example prompts** ("utterances"), e.g.:

| Route (default) | Tier | Example prompts (excerpt) |
|---|---|---|
| `casual-chat` | `LOCAL` | "Hello, how are you today?", "Bonjour, comment ça va ?" |
| `drafting-and-summaries` | `CLOUD_ENTRY` | "Summarize this article…", "Résume ce texte…" |
| `code-and-analysis` | `CLOUD_PREMIUM` | "Refactor this Java service…", "Analyse la complexité…" |

The request is embedded with the **same local Ollama embedding model as the
semantic cache** (`nomic-embed-text`) and compared to every example with cosine
similarity; the route holding the closest example wins
(**max-over-utterances**, more robust than centroids when a route's examples
are diverse). Below `route-similarity-threshold` (default **0.60**) the
heuristic decides; on embedding failure it also falls back to the heuristic,
so routing never breaks because Ollama is unreachable.

Properties of this approach:

- **Language-independent**: similarity happens in embedding space, not on
  keywords — "résume ce texte" matches a summarization route whose examples
  are English (within the embedding model's multilingual ability;
  `nomic-embed-text` is English-centric, so keep bilingual examples per route
  or swap in a multilingual embedding model for more languages).
  **Measured since v2 batch 5, and not in the expected direction**: with the
  default routes, English prompts reach a mean best-route similarity of 0.538
  against 0.647 for French, so **82 % of English prompts fall below the 0.60
  threshold** (French: 14 %) and are decided by the heuristic instead. Routing
  accuracy follows: 45 % English against 80 % French. The limiting factor is the
  fixed threshold, not language coverage — see
  [`evaluation.md`](evaluation.md), and batch 3 for calibrating it on data.
- **N routes, any tier**: unlike premium keywords (which could only force
  premium), routes target any tier and any number of routes is allowed.
- **Cheap**: one local embedding call per uncached request, no LLM call. The
  per-route example embeddings are indexed **in memory** (a few dozen vectors —
  no pgvector involved) and rebuilt automatically when the routes change.
- **Hot-configurable**: routes, tiers, examples and the threshold are editable
  live from the dashboard / admin API.

### Heuristic — `HeuristicComplexityClassifier`

Zero cost, zero latency; also the fallback of the two smarter strategies. In
order:

1. blank/null → `LOCAL`;
2. contains a code fence (` ``` ` or `~~~`) → `CLOUD_PREMIUM`;
3. contains any **premium keyword** (case-insensitive `contains`) → `CLOUD_PREMIUM`;
4. length > `premium-length-threshold` (default 500) → `CLOUD_PREMIUM`;
5. length > `entry-length-threshold` (default 100) → `CLOUD_ENTRY`;
6. else → `LOCAL`.

The default premium keyword list is **bilingual EN/FR** (e.g. `refactor`,
`architecture`, `analyze`/`analyser`, `debug`, `security`/`sécurité`,
`vulnerability`/`vulnérabilité`, `algorithm`/`algorithme`, …) so French user
prompts classify correctly. This is runtime *data*, intentionally not removed
during the project's English migration.

### LLM — `LlmComplexityClassifier`

Calls a small/cheap model (the `classifierClient`, defaulting to the
`CLOUD_ENTRY` model at temperature 0) and parses a **Structured Output**
(`ClassificationResult`) into a tier. On no-tier/failure it falls back to the
heuristic when `fallback-to-heuristic=true` (default), otherwise to
`CLOUD_PREMIUM` (fail safe toward answer quality). `ClassifierProperties` holds the
system prompt and rules.

### Future work: cascade routing

Planned evolution (approach "C"): instead of a single strategy, chain them by
increasing cost with confidence gates — deterministic signals (code fences,
length) → embedding routes → **escalate to the LLM classifier only when the
best route similarity lands in an ambiguous band** (e.g. between the threshold
and threshold + 0.1). This mirrors the vLLM Semantic Router architecture:
each stage is more expensive but rarely reached.
`DelegatingComplexityClassifier` is the intended seam — a `cascade` strategy
would live there, reusing the three existing classifiers unchanged. See
[`../developpment/roadmap-v2.md`](../developpment/roadmap-v2.md) for the planned
implementation with calibrated gates.

The request embedding is **already shared**: `MemoizingEmbeddingModel` memoizes
it per request, so the cache search, this classifier and the cache store use one
vector instead of three
([ADR 0007](adr/0007-memoized-embedding-model.md)).

## The RoutingAdvisor

`RoutingAdvisor implements CallAdvisor, StreamAdvisor`, `getOrder()` =
`HIGHEST_PRECEDENCE + 1` (right after the cache). On `adviseCall`:

1. Extract the user text; blank → pass through.
2. `tier = classifier.classify(userText)`.
3. `candidates = modelRegistry.findByTier(tier)`. If empty, log and pass through
   (use the default model).
4. Otherwise take the first candidate and **rewrite the prompt** with that model
   id (`reroutePrompt` preserves temperature/maxTokens/topP), then
   `chain.nextCall(routedRequest)`.

So the **requested `model` is a hint**: the router overrides it with the tier's
configured model id. `adviseStream(...)` mirrors this (Phase 7.5) — it reroutes the
streamed prompt the same way.

## Traced decisions (v2 batch 2)

Each routing decision is written to `routing_decision`: the chosen tier and
model, the configured strategy alongside the one that actually decided, the
justification as JSONB, a one-word `decision_reason`
(`MATCH` · `BELOW_THRESHOLD_FALLBACK` · `ERROR_FALLBACK` · `NO_MODEL_FOR_TIER`),
the decision-only latency, and the version of the rules in force. See
[`data-model.md`](data-model.md).

Two things it deliberately does **not** do. It never blocks or throws — a
database that is down costs the trace, not the completion. And **a cache hit
produces no routing decision at all**, because the cache short-circuits upstream
of the router; that is a fact about the architecture, not a gap in the trace.

## Hot configuration

The router config is changeable at runtime (no restart):

- `RoutingConfig` (domain) = strategy + entry/premium length thresholds + premium
  keywords + route similarity threshold + the semantic routes
  (`SemanticRoute`: name, tier, examples).
- `RoutingConfigUseCase` / `RoutingConfigService` read and update it;
  `ClassifierRoutingConfigAdapter` bridges it to the live `ClassifierProperties`,
  so updates take effect on the next request.
- Exposed via `GET/PUT /v1/admin/routing` and the dashboard (see
  [`api-reference.md`](api-reference.md)).

## Multi-provider egress (Phase 7.2, generalized in Phase 8)

Egress is **bring-your-own-model-mix**. Provider *instances* are declared under
`gatewai.providers.<name>` (`type` = `anthropic` | `openai` | `openai-compatible`
| `ollama`, plus `api-key`/`base-url` as the type requires), and each model
registry entry references an instance by name. `EgressProviderConfiguration`
builds one `ChatModel` per instance **actually referenced by the registry** —
several Ollama or vLLM servers, cloud vendors, any combination — and validates
the whole configuration at startup (fail-fast: empty tier, duplicate model id,
missing instance, missing credentials).

A `@Primary` `DelegatingChatModel` (`infrastructure/llm`) is what the Spring AI
`ChatClient` is built on. The router only rewrites the prompt's **model id**; the
delegating model resolves that id's registry entry, looks up its provider
instance and dispatches to it. By default everything is **local-first**: the
three tiers map to three Qwen sizes on the bundled Ollama, so the gateway works
with zero API keys. Cloud is opt-in by repointing a tier's registry entry.

There is **no fallback provider**: a model id absent from the registry (or
mapping to an unbuilt instance) raises `UnknownModelException`, returned to the
client as an OpenAI-style 400 (`unknown_model`). Clients may also pin any
registered model id directly — routing only rewrites it when it classifies the
prompt.

Implementation note: `OllamaChatModel` hard-casts the prompt options to
`OllamaChatOptions`, so the delegating model rebuilds the prompt with native
Ollama options (model, temperature, top-p, num-predict) before delegating to an
`ollama`-type instance; Anthropic and OpenAI merge the generic options as-is.
Ollama instances pull their registry models at startup per
`gatewai.providers.<name>.pull-model-strategy` (`when_missing` by default).

## Configuration reference

`gatewai.classifier.*`: `strategy` (`embedding`|`heuristic`|`llm`), `model-id`
(blank → entry model), `temperature`, `fallback-to-heuristic`,
`entry-length-threshold`, `premium-length-threshold`, `premium-keywords`,
`route-similarity-threshold` (0..1, default 0.60),
`routes[n].name` / `routes[n].tier` / `routes[n].examples[m]` (defaults with
bilingual EN/FR examples are defined in `ClassifierProperties`).
`gatewai.providers.<name>.*`: `type` (`anthropic`|`openai`|`openai-compatible`|`ollama`),
`api-key`, `base-url`, `pull-model-strategy` (ollama only).
`gatewai.models.registry.<key>.*`: `provider` (a `gatewai.providers` instance name),
`model-id` (unique), `cost-per-1k-tokens`, `energy-intensity`, `tier`.
