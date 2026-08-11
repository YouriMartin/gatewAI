# Testing & quality

The quality gate is `./mvnw verify` = **Checkstyle** + **tests** + **SpotBugs**.
Architecture rules are themselves a test (ArchUnit).

## Test taxonomy

| Layer | Style | Examples |
|---|---|---|
| `domain/model` | pure unit (no Spring) | `CarbonCalculatorTest`, `CarbonAwareZoneSelectorTest`, `ModelDefinitionTest`, `RequestContextTest`, `LlmRequestTest` |
| `application/service` | unit + Mockito | `ChatCompletionServiceTest`, `GreenReportServiceTest`, `ApiClientAdminServiceTest`, `RoutingConfigServiceTest`, `DeferredChatServiceTest` |
| `infrastructure` | unit / slice | `SemanticCacheAdvisorTest`, `RoutingAdvisorTest`, `HeuristicComplexityClassifierTest`, `LlmComplexityClassifierTest`, `PropertiesModelRegistryTest`, `ChatClientConfigurationTest`, `ClassifierRoutingConfigAdapterTest`, carbon providers, dispatch, metrics, JPA adapters |
| `adapter/in/web` | MockMvc integration | `ChatCompletionControllerTest`, `GreenReportControllerTest` |
| `adapter/in/mcp` | unit + Mockito | `GatewayMcpToolsTest` |
| context / arch | boot + ArchUnit | `GatewaiApplicationTests`, `ArchitectureTest` |
| `eval` | decision-quality harness on labelled data | `EvaluationHarnessTest`, `VectorFixtureTest` |

Roughly 370+ tests run in the default build. Per the project convention, **REST
controllers are integration-tested** (MockMvc) and **trivial mappers are not unit
tested**; everything else has unit coverage.

## Unit vs integration split

Tests that need **external services** (Postgres/pgvector, Ollama, a real model)
are tagged `@Tag("integration")`: `VectorStoreSmokeTest`, `EmbeddingModelSmokeTest`,
`ChatClientSmokeTest`, `ActuatorHealthSmokeTest`, and `ContextLoadsTest`.

- Default (`./mvnw test` / `verify`) **excludes** the `integration` group
  (`maven-surefire-plugin` `<excludedGroups>integration</excludedGroups>`), so the
  standard build needs **no Node and no containers** and stays fast.
- The **`it` profile** flips this (`<groups>integration</groups>`) to run only the
  integration tests against real infra: `./mvnw -Pit test`.

`ContextLoadsTest` (Phase 7.3) boots the **full Spring context** and asserts the
bean graph wires — it makes no provider call. It exists because the default suite
never refreshes the context, so two startup bugs (an MCP `ToolCallbackProvider`
cycle and a missing `CarbonAwareZoneSelector` bean) once slipped through to the
first container run. `ChatClientSmokeTest` additionally carries
`@EnabledIfEnvironmentVariable(ANTHROPIC_API_KEY)`, so it is skipped (no paid call)
when no key is set.

## Continuous integration

`.github/workflows/ci.yml` runs two jobs on push/PR:

- **build** — `./mvnw -DskipFrontend verify` (unit tests + Checkstyle + SpotBugs),
  fast and infra-free.
- **integration** — `./mvnw -Pit test` against **Postgres (pgvector) + Ollama**
  service containers (the embedding and a tiny chat model are pulled first). This
  is the wiring-regression guard: a context that fails to refresh fails the build.
  `ANTHROPIC_API_KEY` is intentionally unset, so the Claude-calling smoke test is
  skipped — the context still loads (the Anthropic model bean is created without
  calling out).

> Note: the integration job relies on Spring AI's pgvector store
> (`initialize-schema=true`) to `CREATE EXTENSION vector` itself (the CI Postgres
> has no `init.sql` mounted); the `dev` service-container user is a superuser, so
> this succeeds.

Since v2 batch 0.1 the integration job is also the **migration guard**: booting
the context runs Flyway and then validates every JPA entity against the migrated
schema (`ddl-auto=validate`). An entity changed without its migration fails the
build there — it can no longer be papered over by `ddl-auto=update`.

## `mock` profile — run with no provider, no key, no cost (Phase 7.4)

Activate the `mock` Spring profile (`SPRING_PROFILES_ACTIVE=mock`) to swap the
egress for `MockEchoChatModel` — a deterministic echo. The mock sits at the
`ChatModel` level (not `LlmClient`), so the **advisor chain still runs**: the
semantic cache and the router execute around it, the response carries the routed
model id and token counts, and green accounting / persistence / reporting behave
realistically. The only thing skipped is the paid provider call.

```bash
docker run -e SPRING_PROFILES_ACTIVE=mock … gatewai   # no ANTHROPIC_API_KEY needed
```

Use it for demos, dashboard/UI work, and plumbing tests. It still needs the local
embedding model (for the cache) and Postgres; it just never calls Anthropic or a
real Ollama chat model. (`DelegatingChatModel` is `@Profile("!mock")`, so exactly
one `@Primary` egress is active.)

## Decision quality is a test too (v2 batch 5)

`EvaluationHarnessTest` scores the **real** routing and cache decision code
against 600 hand-labelled cases and fails the build when a metric falls below
`src/test/resources/eval/baselines.json`. It is an ordinary unit test — it runs in
the default suite, in ~0.15 s, with no Ollama and no database — because the
embedding model is replaced by vectors recorded once and committed under
`src/test/resources/eval/fixtures/`.

Each run writes `target/eval/report.json` and `report.md`; CI uploads both and
pastes the Markdown into the job summary, which is what makes two commits
comparable.

Editing a dataset or a route example invalidates the fixtures — the harness then
fails, naming the command that re-records them:

```bash
docker compose up -d ollama
./mvnw -Pit test -Dtest=EvalFixtureRecorderTest -Deval.record=true
```

`EvalFixtureRecorderTest` is the only part that needs infrastructure. It is tagged
`integration` **and** gated on `-Deval.record=true`, so no automated run can
rewrite committed fixtures. Full method, labelling conventions, current numbers
and limits: [`evaluation.md`](evaluation.md).

## Architecture tests (ArchUnit)

`ArchitectureTest` declares the onion architecture (`domainModels`,
`domainServices`, `applicationServices`, and each inbound/outbound `adapter`
package) and asserts the dependency rules. A new adapter package must be
**registered there** or the build fails — which is exactly what keeps the
hexagonal layering honest (e.g. the `adapter/in/mcp` package was added to it when
MCP shipped). See [`architecture.md`](architecture.md).

## Static analysis & style

- **Checkstyle** (`maven-checkstyle-plugin`) — `validate` phase, **fail-fast**,
  config `checkstyle.xml` (Google style + overrides). Because it runs at
  `validate`, even `package` needs `checkstyle.xml` present (relevant to the
  Docker build — see [`build-and-packaging.md`](build-and-packaging.md)).
- **SpotBugs** (`spotbugs-maven-plugin`) — `verify` phase, effort=max,
  threshold=low, with `spotbugs-exclude.xml`.
- **JSpecify null-safety** annotations are used across the codebase.

## Running

```bash
./mvnw test            # unit tests, Checkstyle (validate) — fast, no infra
./mvnw verify          # + SpotBugs (and the frontend build via package)
./mvnw -Pit test       # integration smoke tests (need Postgres/Ollama)
./mvnw … -DskipFrontend  # back-end-only, skip the Node/Vite build
```

## Native & manual validation

Full GraalVM native compilation is validated in a dedicated CI, not the default
build (see [`native.md`](native.md)). Reflection hints have their own test
(`NativeRuntimeHintsTest`) using `RuntimeHintsPredicates`.
