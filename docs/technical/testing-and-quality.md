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

Roughly 220+ tests run in the default build. Per the project convention, **REST
controllers are integration-tested** (MockMvc) and **trivial mappers are not unit
tested**; everything else has unit coverage.

## Unit vs integration split

Tests that need **external services** (Postgres/pgvector, Ollama, a real model)
are tagged `@Tag("integration")`: `VectorStoreSmokeTest`, `EmbeddingModelSmokeTest`,
`ChatClientSmokeTest`, `ActuatorHealthSmokeTest`.

- Default (`./mvnw test` / `verify`) **excludes** the `integration` group
  (`maven-surefire-plugin` `<excludedGroups>integration</excludedGroups>`), so the
  standard build needs **no Node and no containers** and stays fast.
- The **`it` profile** flips this (`<groups>integration</groups>`) to run only the
  smoke tests against real infra: `./mvnw -Pit test`.

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
