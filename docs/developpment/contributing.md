# Contributing

How to build, test and change gatewAI, and the conventions to respect. For the
architecture itself, read [`../technical/architecture.md`](../technical/architecture.md)
first.

## Prerequisites

- **JDK 25** (the build targets Java 25).
- **Docker** + **Docker Compose** (for the infra and integration tests).
- No global Node needed: the frontend build installs a **pinned, local Node** via
  Maven.

## Build & test

```bash
./mvnw test            # unit tests + Checkstyle (validate) — fast, no Node, no infra
./mvnw verify          # + SpotBugs, and the frontend build (package)
./mvnw -Pit test       # integration smoke tests (need Postgres + Ollama running)
./mvnw … -DskipFrontend  # back-end-only: skip the Node/Vite build
./mvnw spring-boot:run   # run locally; Boot auto-starts infra via compose.yaml
```

**Always run `./mvnw test` before committing.** The quality gate is
`./mvnw verify` (Checkstyle + tests + SpotBugs). See
[`../technical/testing-and-quality.md`](../technical/testing-and-quality.md) and
[`../technical/build-and-packaging.md`](../technical/build-and-packaging.md).

## Conventions

- **English only.** All code, comments, docs, commit messages and UI strings are in
  English (project-wide since 2026-06-28). The one intentional exception is the
  **bilingual routing keyword list** (`ClassifierProperties.premiumKeywords`), which
  classifies French *user* prompts — that is runtime data, not prose.
- **Java**: `record`s for DTOs; Spring AI **immutable builders** (no setters);
  Jackson 3 lives under `tools.jackson` (not `com.fasterxml.jackson`).
- **Secrets** via environment variables, **never committed** (`ANTHROPIC_API_KEY`,
  `ELECTRICITY_MAPS_TOKEN`, …).
- **Commit messages**: short, English, **imperative mood** (e.g. "Add per-client
  rate limiting"). Keep changes focused.
- **Tests**: `{Class}Test.java` in the mirror package under `src/test/java`. Unit
  test everything except REST controllers (integration-tested) and trivial mappers.
  Tag tests needing real infra with `@Tag("integration")`.

## Respect the hexagonal rules (enforced by ArchUnit)

`ArchitectureTest` will fail the build if these are violated:

- `domain` depends on **nothing** (no Spring, no JPA, no Spring AI).
- `application` depends on `domain` only.
- `infrastructure` implements the `out` ports of `domain`.
- `adapter.in.*` calls the `in` ports of `domain`.

> Adding a new adapter package? **Register it in `ArchitectureTest`** (the onion
> `adapter(...)` list) or the build fails — this is by design.

## How to make common changes

### Add an outbound provider (egress)
Implement/extend the LLM wiring in `infrastructure/llm` behind the `LlmClient` out
port; add the model to the registry (`gatewai.models.registry.*`) with its tier.
Enabling a local Ollama egress also means re-enabling its chat auto-config and
wiring a local `ChatClient` (currently commented out in `ChatClientConfiguration`)
— see [`../technical/routing.md`](../technical/routing.md).

### Add an inbound channel (ingress)
Create a new `adapter/in/<name>` package that calls the existing `in` ports (this
is exactly how the MCP ingress was added), and register it in `ArchitectureTest`.

### Add a tunable
Prefer `@ConfigurationProperties` (`gatewai.*`) with sane defaults in
`application.properties`, documented in the relevant technical doc.

### Change the dashboard
Work in `src/main/frontend` (`npm run dev`, Vite on `:5173`, proxies `/v1 → :8080`).
Keep it light (no heavy chart-library runtime) — consistent with the green stance.

## Documentation

Docs are split into [`../technical/`](../technical/),
[`../functional/`](../functional/) and `developpment/` — see
[`../README.md`](../README.md). When you change behaviour, update the matching doc
and keep it **code-anchored and honest about limits**. New structuring decisions
get an ADR in [`../technical/adr/`](../technical/adr/).

## Before opening a PR

1. `./mvnw verify` is green (Checkstyle + tests + SpotBugs).
2. New/changed behaviour is covered by tests and reflected in the docs.
3. No secrets committed; no French prose introduced.
4. ArchUnit passes (layering respected, new adapters registered).
