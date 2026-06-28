# gatewAI

Open-source, self-hosted LLM proxy for enterprises.
It secures, caches, routes and measures the carbon footprint of AI requests.

## Why gatewAI?

Enterprises are adopting LLMs but face three problems:

- **Cost** — redundant requests billed twice (or three times)
- **Privacy** — data flows through third parties with no control
- **Environmental impact** — no visibility into the carbon footprint of AI

gatewAI solves all three in a single infrastructure component.

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
   │ Claude /     │  │ Ollama        │ │ PostgreSQL + pgvector │
   │ OpenAI (API) │  │ (embeddings + │ │ vector cache +        │
   │              │  │  local model) │ │ relational metrics    │
   └──────────────┘  └──────────────┘  └──────────────────────┘
```

**Ingress** (OpenAI or MCP format) and **egress** (LLM provider) are
independent. Any existing client SDK works by only changing the `base_url`.
Per-layer details: see [`docs/`](docs/) (carbon, observability, MCP, native
image).

## Tech stack

| Component | Technology |
|---|---|
| Language | Java 25 (Virtual Threads + Scoped Values) |
| Framework | Spring Boot 4, Spring AI 2.0 |
| Database | PostgreSQL + pgvector (vector cache + metrics) |
| Embeddings | Ollama + nomic-embed-text (768 dim, 100% on-premise) |
| Local infra | Docker Compose |
| Build | Maven (wrapper included) |

## Prerequisites

- **Docker** and **Docker Compose** (enough for plug & play mode)
- **Java 25** + Maven *(only for development mode)*
- An Anthropic API key (`ANTHROPIC_API_KEY`)

## Quick start — plug & play (all in containers)

No JDK required: Docker builds the app (back end + dashboard) and starts the whole
stack.

```bash
# 1. Clone the project
git clone https://github.com/your-user/gatewAI.git
cd gatewAI

# 2. Configure the secrets
cp .env.example .env
$EDITOR .env            # set ANTHROPIC_API_KEY

# 3. Start the full stack (gateway + Postgres/pgvector + Ollama)
docker compose -f docker-compose.yml up --build
```

> `compose.yaml` (infra only) is used in dev mode and takes precedence when you
> run `docker compose` without `-f`; the full stack is therefore invoked
> explicitly with `-f docker-compose.yml`.

- Dashboard: <http://localhost:8080/>
- OpenAI API: `POST http://localhost:8080/v1/chat/completions`
- MCP server: `http://localhost:8080/mcp` (see [`docs/mcp.md`](docs/mcp.md))
- Health: <http://localhost:8080/actuator/health>

On the **first** start, the gateway downloads the embedding model
(`nomic-embed-text`) from Ollama — give the health check time to pass. The
bootstrap **admin API key** is printed once in the logs:

```bash
docker compose -f docker-compose.yml logs gateway | grep "Admin API key"
```

## Development mode (local JDK, hot reload)

```bash
# Boot starts Postgres + Ollama via compose.yaml; the app runs on the JVM
export ANTHROPIC_API_KEY=sk-ant-...
./mvnw spring-boot:run

# Dashboard with hot reload (Vite, proxies /v1 → :8080)
cd src/main/frontend && npm run dev
```

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
Measures the footprint (tokens → kWh → gCO2) and computes the CO2 avoided thanks
to the cache and routing. CSRD-compatible reporting export.

## License

[MIT](LICENSE)
