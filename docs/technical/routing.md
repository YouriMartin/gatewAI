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
| `llama3` | ollama | llama3 | `LOCAL` | 0.0 | 0.001 |

> The energy intensities are **placeholders** (see
> [`green-accounting.md`](green-accounting.md)). The model ids are configurable.

`ModelRegistry` (out port) offers `findByTier`, `findByModelId`, `findByKey`,
`allModels`.

## Classification

`ComplexityClassifier` (out port) has two implementations, selected by
`gatewai.classifier.strategy`:

### Heuristic (default) — `HeuristicComplexityClassifier`

Zero cost, zero latency. In order:

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
configured model id. Streaming delegates unchanged.

## Hot configuration

The router config is changeable at runtime (no restart):

- `RoutingConfig` (domain) = strategy + entry/premium length thresholds + premium
  keywords.
- `RoutingConfigUseCase` / `RoutingConfigService` read and update it;
  `ClassifierRoutingConfigAdapter` bridges it to the live `ClassifierProperties`,
  so updates take effect on the next request.
- Exposed via `GET/PUT /v1/admin/routing` and the dashboard (see
  [`api-reference.md`](api-reference.md)).

## Important caveat: single egress by default

The egress `ChatModel` auto-configured by default is **Anthropic**. The wired
`ChatClient`s are `premiumClient` and `cheapCloudClient` (both Claude); the
**local (Ollama) client is intentionally commented out** in
`ChatClientConfiguration` (it needs a running Ollama chat model). Practically:

- `CLOUD_PREMIUM`/`CLOUD_ENTRY` route to Claude models and work out of the box.
- `LOCAL` routing has **no local egress by default** — enabling it requires
  running Ollama with a chat model and re-enabling its auto-config. Until then,
  keep registry tiers pointing at models the active provider serves. See
  [`../functional/limitations.md`](../functional/limitations.md).

## Configuration reference

`gatewai.classifier.*`: `strategy` (`heuristic`|`llm`), `model-id` (blank → entry
model), `temperature`, `fallback-to-heuristic`, `entry-length-threshold`,
`premium-length-threshold`, `premium-keywords`.
`gatewai.models.registry.<key>.*`: `provider`, `model-id`, `cost-per-1k-tokens`,
`energy-intensity`, `tier`.
