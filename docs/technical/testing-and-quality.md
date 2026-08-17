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
| calibration | conformal quantile, guarantees, degradation | `ConformalQuantileTest`, `ConformalCalibrationTest`, `ConformalCalibrationServiceTest`, `SemanticCacheConformalTest` |
| dispatch queue | claiming, leases, crash recovery | `DeferredChatServiceTest`, `JpaDeferredJobStoreTest`, `DeferredJobJsonTest`, `JpaDeferredJobStoreClaimTest` (integration) |
| rate limiting | both stores, the filter's scope, the check timer | `InMemoryRateLimiterTest`, `RateLimitFilterTest`, `PostgresRateLimiterTest` (integration) |
| clustered scheduling | who runs a periodic job, and what a concurrent cold start seeds | `DecisionPurgeWorkerTest`, `AdminSeedRunnerTest`, `AdvisoryLeaderLockTest` (integration) |
| decisions | what is traced, how it is read back and explained | `AsyncDecisionRecorderTest`, `CacheDecisionTracerTest`, `JustificationJsonTest`, `JpaDecisionHistoryTest`, `DecisionExplanationServiceTest`, `AdminDecisionControllerTest` |
| explanation | occlusion, segmentation, counterfactuals | `OcclusionTest`, `PromptSegmentationTest`, `OcclusionAttributionServiceTest`, `CounterfactualsTest`, `RouteCounterfactualServiceTest`, `InMemoryAttributionCacheTest` |
| embedding | the real ONNX model, in-process, and its native-image hints | `InProcessEmbeddingModelTest`, `EmbeddingNativeRuntimeHintsTest` |

**599 tests** run in the default build (v3 lot B.4). Per the project convention,
**REST controllers are integration-tested** (MockMvc) and **trivial mappers are
not unit tested**; everything else has unit coverage.

Two conventions the v2 decision work added, both of them deliberate:

- **Explanation services are tested against a bag-of-words embedder**, not a
  mock returning canned vectors. Similarities are then computable by hand, so
  every assertion is a subtraction a reviewer can check rather than a number
  copied from a run.
- **Sensitive-by-construction outputs get a named test.** The counterfactual
  suite asserts that every returned utterance is a *configured route example* and
  none is any part of the request — an explanation quoting one client's prompt
  back to another would be a data leak wearing the costume of a feature.

## Unit vs integration split

Tests that need **external services** (Postgres/pgvector, Ollama, a real model)
are tagged `@Tag("integration")`: `VectorStoreSmokeTest`, `ChatClientSmokeTest`,
`ActuatorHealthSmokeTest`, `ContextLoadsTest`, and — since v3 lot B —
`JpaDeferredJobStoreClaimTest`, `PostgresRateLimiterTest` and
`AdvisoryLeaderLockTest`.

Those last three are tagged for a reason worth stating: what they test **is** the
SQL. `FOR UPDATE SKIP LOCKED` handing each job to exactly one of two concurrent
workers, a bulk update requeueing an expired lease, one token bucket shared by two
limiter instances, and `pg_try_advisory_xact_lock` refusing a second node cannot be
demonstrated against a mock repository — a passing mock would only prove the mock
agrees with itself.

`AdvisoryLeaderLockTest` is also the only thing proving the lock is taken on the
**same connection as the work**: if `JdbcTemplate` did not join the transaction,
the lock would be released the instant it was taken and both threads would run.
Its timing is not left to chance — the second node always asks while the first is
inside its job, and the first waits for the answer before releasing.

`PostgresRateLimiterTest` also covers the trap of a *persisted* bucket: it carries
the bandwidth it was created with, so a test asserts that editing
`requests-per-minute` actually changes what is enforced instead of being a setting
that silently does nothing.

**The in-memory limiter's own test kept its assertions verbatim** across B.3 (only
the constructor name moved, to `InMemoryRateLimiter`), which is how the batch shows
it did not regress the single-node path while adding the shared one.

Since v3 lot A the **embedding tests are not among them**. `EmbeddingModelSmokeTest`
needed Ollama and is replaced by `InProcessEmbeddingModelTest`, an ordinary unit
test that loads the shipped ONNX model, embeds text and checks the vector width —
with nothing listening on any port. It also asserts the committed model is
megabytes rather than a placeholder, which is how a failed or truncated
**build-time fetch** shows up — and how `spring-ai-transformers` ships its own
bundled model (a 133-byte pointer). Model load costs ~1 s; the tests run in ~5 s.

- Default (`./mvnw test` / `verify`) **excludes** the `integration` group
  (`maven-surefire-plugin` `<excludedGroups>integration</excludedGroups>`), so the
  standard build needs **no Node and no containers** and stays fast.
- The **`it` profile** flips this (`<groups>integration</groups>`) to run only the
  integration tests against real infra: `./mvnw -Pit test`.

`ContextLoadsTest` (Phase 7.3) boots the **full Spring context** and asserts the
bean graph wires — it makes no provider call. It exists because the default suite
never refreshes the context, so two startup bugs (an MCP `ToolCallbackProvider`
cycle and a missing `CarbonAwareZoneSelector` bean) once slipped through to the
first container run. A third joined them in v2 batch 3: the calibration service
asking the classifier for its route scores, while the classifier asks the
calibration for its threshold. 407 unit tests were green; the context refused to
start. That is the entire justification for keeping this job.

`ChatClientSmokeTest` additionally carries
`@EnabledIfEnvironmentVariable(ANTHROPIC_API_KEY)`, so it is skipped (no paid call)
when no key is set.

## Continuous integration

`.github/workflows/ci.yml` runs two jobs on push/PR:

- **build** — `./mvnw -DskipFrontend verify` (unit tests + Checkstyle + SpotBugs),
  fast and infra-free.
- **integration** — `./mvnw -Pit test` against **Postgres (pgvector) + Ollama**
  service containers. Since v3 lot A no embedding model is pulled: Ollama is only
  the chat egress the context wires and never calls. The embedding model is
  fetched by Maven and cached with `~/.m2`, so it costs one download per cache
  generation. This
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

### Why not Testcontainers

The v2 plan expected a Testcontainers-backed Postgres for the migration and
replay tests. The project has none, and this is a decision rather than an
omission: **CI service containers already provide the real Postgres+pgvector and
Ollama**, the `it` profile points at them by configuration, and the same command
runs locally against `docker compose`. Adding Testcontainers would buy container
lifecycle management the build does not need, at the cost of a Docker daemon
inside every test run and a second way to configure the same two dependencies.

What that leaves uncovered is worth naming: there is **no single test that walks
a request from ingress to a persisted decision and back out through
`/v1/admin/decisions`**. The path is covered in pieces — recorder
(`AsyncDecisionRecorderTest`), JSONB mapping (`JustificationJsonTest`), merge
(`JpaDecisionHistoryTest`), HTTP contract and admin-only access
(`AdminDecisionControllerTest`) — with `ContextLoadsTest` proving the pieces wire
together against real infrastructure. The seam nobody tests end to end is
therefore the *composition*, and that is the honest statement of the gap.

**Multi-node behaviour is partly in the same category** (v3 lot B). Where the
guarantee lives in SQL, there is a test that runs the SQL:
`JpaDeferredJobStoreClaimTest` proves the concurrent claim and the lease sweep on
a real database. Where it lives in a sequence of HTTP calls across two processes —
a `PUT` on one node converging on another, a job submitted on A and completed by B
after a restart — there is none: the pieces are unit-tested against fakes
(`PersistentRoutingConfigPortTest`, `JpaRoutingConfigStoreTest`,
`JpaDeferredJobStoreTest`) and the two-node behaviour was verified by hand, with
the numbers written down in [`clustering.md`](clustering.md). B.5 is where that
becomes a repeatable scenario.

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

Use it for demos, dashboard/UI work, and plumbing tests. Since v3 lot A it needs
**Postgres alone** — the embedding model is in the jar — so the whole gateway
runs on one container with no API key and no model server. It just never calls
Anthropic or a real Ollama chat model. (`DelegatingChatModel` is `@Profile("!mock")`, so exactly
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
./mvnw test -Dtest=EvalFixtureRecorderTest -Deval.record=true
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
build (see [`native.md`](native.md)). Reflection and resource hints have their own
tests (`NativeRuntimeHintsTest`, `EmbeddingNativeRuntimeHintsTest`) using
`RuntimeHintsPredicates` — they prove the hints are *declared*, never that an
image works.
