# gatewAI

[![CI](https://github.com/YouriMartin/gatewAI/actions/workflows/ci.yml/badge.svg)](https://github.com/YouriMartin/gatewAI/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Java 25](https://img.shields.io/badge/Java-25-orange.svg)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0-6db33f.svg)

**The LLM gateway you can audit.**

Every cache hit and every routing decision is traced, explained and replayable. One
OpenAI-compatible endpoint in front of your local and cloud models — Spring Boot,
with PostgreSQL as its only infrastructure dependency.

## Quickstart

Docker with Compose v2 is all it takes — no clone, no build, no API key, no model
download.

<!-- quickstart-check: CI runs the block below as written (scripts/quickstart-check.sh) -->
```bash
curl -fsSLO https://raw.githubusercontent.com/YouriMartin/gatewAI/main/docker-compose.quickstart.yml
docker compose -f docker-compose.quickstart.yml up -d --wait
curl -si http://localhost:8080/v1/chat/completions -H "Authorization: Bearer gw_quickstart-demo-key" -H "Content-Type: application/json" -d '{"model":"gatewai-auto","messages":[{"role":"user","content":"What does a semantic cache do?"}]}'
```

**What you should see.** Run the last command twice. The first answer comes from the
model and the second from the cache, without a model call:

```
HTTP/1.1 200
X-Request-Id: 1853513f-…
X-GatewAI-Cache: MISS
X-GatewAI-Model: qwen2.5:1.5b
…
{"object":"chat.completion","model":"qwen2.5:1.5b","choices":[{"message":{"role":"assistant","content":"[mock] echo: What does a semantic cache do?"}, …

HTTP/1.1 200
X-Request-Id: 944d7370-…
X-GatewAI-Cache: HIT
…
```

From `up` to that first answer: **22 s** (three runs, 21.8–22.1 s; images already
pulled, fresh volume), measured 2026-10-25 on the maintainer's workstation — AMD
Ryzen 7 5800X, 32 GB RAM, Fedora 43, Docker 29.8.2 — not on a clean machine.

That default is the **`mock`** preset: a built-in echo stands in for the model (the
answer reads `[mock] echo: …`, under the model id the router picked) while the
cache, the router, the traces and the
[dashboard](http://localhost:8080/) run for real. Point it at a real model with one
of the two other presets, chosen by environment variables (in a `.env` file next to
the compose file, or exported) — never by editing configuration:

| Preset | Set | Notes |
|---|---|---|
| `mock` (default) | nothing | zero dependency |
| `ollama` | `GATEWAI_PRESET=ollama`, `GATEWAI_MODEL_SMALL`, `_MEDIUM`, `_LARGE` | an Ollama already running on this machine; three distinct models it holds. On Linux, start Ollama with `OLLAMA_HOST=0.0.0.0` so the container can reach it |
| `openai-compatible` | `GATEWAI_PRESET=openai-compatible`, `OPENAI_COMPATIBLE_BASE_URL`, `OPENAI_COMPATIBLE_API_KEY`, the three `GATEWAI_MODEL_*` | vLLM, LM Studio, llama.cpp, OpenRouter, OpenAI… — the base URL is the root that serves `/chat/completions` |

The three models fill the three routing tiers, smallest first. Every variable is
explained at the top of
[`docker-compose.quickstart.yml`](docker-compose.quickstart.yml); the next steps —
your own API keys, a chat UI, MCP — are in
[getting started](docs/functional/getting-started.md). The demo admin key above is
published on `127.0.0.1` only; set `GATEWAI_ADMIN_API_KEY` before exposing the port.

**Clients checked against it** ([full table and method](docs/functional/features.md#client-compatibility)):

| Client | Version | Checked |
|---|---|---|
| curl | 8.15.0 | live — this quickstart, the getting-started and troubleshooting examples |
| Open WebUI | v0.12.0 | live — model list, streamed chats, title task |
| OpenAI Python SDK, Vercel AI SDK, LangChain | sources read 2026-10-08 | request shapes replayed by the test suite, not run end to end |

Read [limitations](docs/functional/limitations.md) before relying on the numbers.

![gatewAI dashboard](docs/assets/dashboard.png)

## Why gatewAI?

A team that puts several providers — local models included — behind one endpoint
has to answer for what that endpoint does: why this request went to the expensive
model, why that one got an answer from the cache, what it cost and what it emitted.
Routing and caching are common. gatewAI keeps the receipts.

- **Every decision is traced.** Each cache lookup and each routing choice is stored
  with its scores, its threshold, the cache scope and the configuration version
  that produced it, and read back as stored rather than recomputed
  ([decision tracing](docs/technical/decision-tracing.md)).
- **And explained.** On demand: which part of the prompt carried the routing
  decision, and how close the next tier came
  ([attribution and counterfactuals](docs/technical/attribution.md)).
- **Thresholds can be calibrated** on labelled data with a stated error rate, and
  the gateway says so when they are not
  ([conformal calibration](docs/technical/conformal-calibration.md)).
- **Cost and carbon per request**, with every coefficient sourced and labelled,
  never presented as measured ([green accounting](docs/technical/green-accounting.md)).
- **Few moving parts.** One Spring Boot jar, embeddings computed in-process, one
  PostgreSQL for the cache, the traces and the metrics. Any mix of Ollama, vLLM,
  OpenAI-compatible servers, OpenAI and Anthropic behind it, local-first by default.

What it does *not* do is listed, up front, in [limitations](docs/functional/limitations.md).

## Architecture

A thin gateway built on a chain of Spring AI **Advisors**. End-to-end view:

```
        ┌──────────────┐        ┌──────────────────┐
        │ OpenAI SDK    │       │ MCP client        │
        │ (base_url →)  │       │ (Claude Desktop…) │
        └──────┬────────┘       └────────┬──────────┘
               │ /v1/chat/completions     │ /mcp
               ▼                          ▼
   ╔══════════════════════ gatewAI ══════════════════════╗
   ║  OpenAI ingress  +  MCP ingress   (API-key auth)    ║
   ║      │                                               ║
   ║      ▼  Spring AI Advisor chain                      ║
   ║  [1] Semantic cache    ─ hit → reply, 0 LLM call    ║
   ║  [2] Smart router      ─ optimal model (cost/CO2)   ║
   ║  [3] Green accounting  ─ € + gCO2 (+ avoided CO2)   ║
   ║      │                                               ║
   ║      ▼ egress                  Svelte dashboard  ◀───╫─ /
   ║  real ChatModel                /actuator/prometheus ◀╫─ Prometheus+Grafana
   ╚══════╪═══════════════════════════════╪══════════════╝
          │                               │
   ┌──────▼──────┐  ┌──────────────┐  ┌───▼──────────────────┐
   │ Any provider │  │ Ollama        │ │ PostgreSQL + pgvector │
   │ mix: Ollama, │  │ (local chat   │ │ vector cache +        │
   │ vLLM, Claude,│  │  models only) │ │ relational metrics    │
   │ OpenAI…      │  │               │ │                       │
   └──────────────┘  └──────────────┘  └──────────────────────┘
   Embeddings run in-process (ONNX, bundled in the jar) — no model server.
```

**Ingress** (OpenAI or MCP format) and **egress** (LLM provider) are
independent. Any existing client SDK works by only changing the `base_url`,
and the egress side is **bring-your-own-model-mix**: local-first by default
(everything runs on the bundled Ollama, zero API keys), with any combination of
provider instances — several Ollama/vLLM servers, Anthropic, OpenAI, any
OpenAI-compatible endpoint — declared in configuration. No vendor is required
or privileged. Per-layer details: see [`docs/`](docs/README.md) (carbon,
observability, MCP, native image).

## Tech stack

| Component | Technology |
|---|---|
| Language | Java 25 (Virtual Threads + Scoped Values) |
| Framework | Spring Boot 4, Spring AI 2.0 |
| Database | PostgreSQL + pgvector (vector cache + metrics) |
| Embeddings | In-process ONNX (DJL + ONNX Runtime), 384 dim, bundled in the jar |
| Local infra | Docker Compose |
| Build | Maven (wrapper included) |

## Build and run from source

- **Docker** and **Docker Compose** for the full stack below; **Java 25** only for
  development mode.

No API key is required: by default all three routing tiers run on the bundled
Ollama. Cloud providers (Anthropic, OpenAI, any OpenAI-compatible endpoint) are
opt-in via the model registry.

### Full stack in containers, with a bundled Ollama

No JDK required: Docker builds the app (back end + dashboard) and starts the whole
stack. Unlike the [quickstart](#quickstart), it builds the image and pulls the
default models.

```bash
# 1. Clone the project
git clone https://github.com/YouriMartin/gatewAI.git
cd gatewAI

# 2. (Optional) configure secrets — admin key, cloud API keys
cp .env.example .env

# 3. Start the full stack (gateway + Postgres/pgvector + Ollama)
docker compose -f docker-compose.yml up --build
```

> `compose.yaml` (infra only) is used in dev mode and takes precedence when you
> run `docker compose` without `-f`; the full stack is therefore invoked
> explicitly with `-f docker-compose.yml`.

- Dashboard: <http://localhost:8080/>
- OpenAI API: `POST http://localhost:8080/v1/chat/completions`
- MCP server: `http://localhost:8080/mcp` (see [`docs/technical/mcp.md`](docs/technical/mcp.md))
- Health: <http://localhost:8080/actuator/health>

On the **first** start, the gateway downloads the three default chat models
(`qwen2.5` 0.5b/1.5b/3b, ~3 GB total) into Ollama — give the health check time
to pass. The embedding model is **not** among them: it ships inside the jar and
runs in-process (v3 lot A), so the cache and the router work before any model
server is up. To route a tier
to a cloud model instead, see the *Egress providers* section of
[`application.properties`](src/main/resources/application.properties).

Your **admin API key** is simply the `GATEWAI_ADMIN_API_KEY` you set in `.env` —
the gateway seeds an admin client with that exact key at startup. Use it as the
`Authorization: Bearer …` token for `/v1/admin/**`.

> If you leave `GATEWAI_ADMIN_API_KEY` blank, a random admin key is generated and
> printed **once** in the logs instead:
> `docker compose -f docker-compose.yml logs gateway | grep "Admin API key"`.
> (A `Using generated security password: <uuid>` line, if present, is an unrelated
> Spring default — not your API key.)

### Development mode (local JDK, hot reload)

The quickest way is the dev environment script (manages infra + backend):

```bash
scripts/dev.sh start      # Postgres + Ollama (compose.yaml, inference profile) + backend
scripts/dev.sh restart    # bounce the backend only (infra stays up)
scripts/dev.sh stop       # stop backend + infra (data kept)
scripts/dev.sh status     # status, URLs; `logs` to tail, `clean` to wipe volumes
```

It reads secrets from `.env`. Equivalent manual steps:

```bash
# Boot starts Postgres via compose.yaml; the app runs on the JVM.
# Local chat egress is opt-in: docker compose --profile inference up -d ollama
./mvnw spring-boot:run
```

For the dashboard with **hot reload** (separate, in either case):

```bash
cd src/main/frontend && npm run dev   # Vite on :5173, proxies /v1 → :8080
```

> Use the dev script **or** the full container stack
> (`docker compose -f docker-compose.yml up`), not both — they share ports and the
> Compose project name.

## Useful commands

```bash
# Tests (fast, no Node, no containers)
./mvnw test

# Full build: tests + Checkstyle + SpotBugs
./mvnw verify

# Infra only, without the app (dev mode)
docker compose -f compose.yaml up -d

# Optional observability stack (Prometheus + Grafana)
docker compose -f docker-compose.observability.yml up -d

# Two replicas behind a load balancer, then the cluster scenario
docker compose -f docker-compose.cluster.yml up --build -d
./scripts/cluster-smoke.sh

# Stop the full plug & play stack
docker compose -f docker-compose.yml down
```

## Features

### Semantic cache
Intercepts similar requests before any LLM call.
Cuts cost and latency by short-circuiting redundant calls.

### Smart routing
Sends each request to the cheapest/leanest model able to handle it:
local (Ollama) for simple tasks, cloud premium for complex ones.

### Carbon accounting
Estimates the footprint (prompt/completion tokens → kWh × PUE → gCO2, at the grid
that served the request) and computes the CO2 avoided thanks to the cache and
routing. CSRD-compatible reporting export. Every coefficient carries its provenance —
`vendor-published`, `modelled` (with its source and read-date) or **excluded from
scope** for self-hosted inference, which the gateway cannot meter and therefore never
reports as a bare `0 gCO2` (see
[limitations](docs/functional/limitations.md#carbon-figures-are-directional-not-audited)
and [the coefficient table](docs/technical/green-accounting.md#the-coefficients-and-where-they-come-from)).
Reports break the emissions down **by grid zone and by provider**, state
location-based Scope 2 explicitly, and name which regions were *assumed* rather than
known — the direct vendor APIs do not disclose one
([ADR 0012](docs/technical/adr/0012-region-on-the-provider-instance.md),
[ADR 0013](docs/technical/adr/0013-sourced-and-labelled-not-measured.md)).

## Documentation

Full index: [`docs/`](docs/README.md). Curated entry points:

**Using it** (`docs/functional/`)
- [Getting started](docs/functional/getting-started.md) — deploy, get a key, first request (OpenAI + MCP)
- [Overview](docs/functional/overview.md) · [Features](docs/functional/features.md) · [Dashboard guide](docs/functional/dashboard-guide.md)
- [Troubleshooting](docs/functional/troubleshooting.md) — every error a client can get, and finding a request by `X-Request-Id`
- [Limitations](docs/functional/limitations.md) — what it does *not* do (read before relying on the numbers)

**Understanding it** (`docs/technical/`)
- [Architecture](docs/technical/architecture.md) — start here: hexagonal layers, advisor chain, request lifecycle
- [API reference](docs/technical/api-reference.md) · [Semantic cache](docs/technical/semantic-cache.md) · [Routing](docs/technical/routing.md) · [Green accounting](docs/technical/green-accounting.md)
- [Security](docs/technical/security.md) · [Build & packaging](docs/technical/build-and-packaging.md) · [Decision records (ADRs)](docs/technical/adr/README.md)

**Working on it** (`docs/developpment/`)
- [Contributing](docs/developpment/contributing.md) — build, test, conventions, how to make common changes
- [Releasing](docs/developpment/releasing.md) · [Changelog](CHANGELOG.md) · [Security policy](SECURITY.md)
- [v4 roadmap](docs/developpment/roadmap-v4.md) — the current plan

## License

[MIT](LICENSE)
