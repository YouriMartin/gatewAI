# Getting started

This guide takes you from nothing to a first answer through gatewAI: deploy, get
an API key, send a request via the OpenAI SDK **and** via MCP, plug in a chat UI,
and open the dashboard.

## 1. Prerequisites

- **Docker** and **Docker Compose** (enough for the plug & play mode below).

That is all — **no API key**: by default all three routing tiers run on the
bundled Ollama (local-first). Cloud providers (Anthropic, OpenAI, any
OpenAI-compatible endpoint) are opt-in via the model registry. No JDK is
required for the container mode; the image builds the app (back end +
dashboard) for you.

## 2. Deploy (plug & play)

```bash
git clone https://github.com/YouriMartin/gatewAI.git
cd gatewAI

cp .env.example .env    # optional: admin key, cloud API keys

docker compose -f docker-compose.yml up --build
```

This starts the full stack: the **gateway** (port 8080), **PostgreSQL + pgvector**
(cache + metrics) and **Ollama** (local chat models). On the first start the
gateway downloads the three default chat models (`qwen2.5` 0.5b/1.5b/3b, ~3 GB
total) into Ollama, so give the health check some time to go green.

> The **embedding** model is not downloaded: since v3 lot A it ships inside the
> jar and runs in-process, which is why the semantic cache and the router work
> the moment Postgres is up. `compose.yaml` (dev infra) starts Postgres alone;
> add `--profile inference` when you want local chat egress too.

> `compose.yaml` (infra only) is used in development mode and takes precedence
> when you run `docker compose` without `-f`; the full stack is therefore invoked
> explicitly with `-f docker-compose.yml`. For local development (run the app on
> the JVM with hot iteration), use `scripts/dev.sh start` — see the root
> `README.md` and [`../developpment/contributing.md`](../developpment/contributing.md).

Check it is up:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP", ...}
```

> **Try it with no API key / no cost.** Set `SPRING_PROFILES_ACTIVE=mock` (e.g. in
> `.env` / the compose `gateway` environment) to use a deterministic echo egress.
> The cache, router, green accounting, reports and dashboard all work — only the
> paid provider call is replaced. Great for a first look, demos, and UI work.

## 3. Get an API key

Every `/v1/**` call must carry a **Bearer API key**. The simplest path is to set
your own **admin key** in `.env`:

```bash
# in .env
GATEWAI_ADMIN_API_KEY=gw_a-long-random-secret-you-choose
```

At startup, gatewAI seeds an admin client with that exact key (idempotently, so
restarts are safe). That value **is** your admin Bearer token — use it directly
for `/v1/admin/**`. Only its hash is stored. Prefix with `gw_` for consistency.

> **Random-key fallback.** If you leave `GATEWAI_ADMIN_API_KEY` blank, a random
> admin key is generated and logged **once** at first start:
> ```bash
> docker compose -f docker-compose.yml logs gateway | grep "Admin API key"
> # ... Admin API key (shown ONCE, copy it now): gw_XXXXXXXXXXXXXXXXXXXXXXXX
> ```
> A separate `Using generated security password: <uuid>` line, if present, is an
> unrelated Spring default — **not** your API key. If the grep is empty, an admin
> already exists; set `GATEWAI_ADMIN_API_KEY` and restart, or reset with
> `psql -U dev -d greenaiproxy -c "DELETE FROM api_client;"` then restart.

Use the admin key directly, or better, create a **dedicated client key** for your
application (admin role required):

```bash
ADMIN=gw_your_admin_key

curl -X POST http://localhost:8080/v1/admin/clients \
  -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"name": "my-app", "admin": false}'
# { "client": { "id": "...", "name": "my-app", ... }, "api_key": "gw_..." }
```

The `api_key` in the response is shown once. Use it as the key for that
application's traffic. Per-client keys are what make the cost/carbon reporting and
cache namespacing per-client meaningful.

## 4. First request — OpenAI-compatible API

gatewAI speaks the OpenAI Chat Completions format. Point any OpenAI client at
`http://localhost:8080/v1` and use your gatewAI key as the API key.

### curl

```bash
KEY=gw_your_app_key

curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "auto",
    "messages": [{"role": "user", "content": "Hello in one sentence."}]
  }'
```

The response is OpenAI-shaped. Two things to notice:

- `model` reflects the model **the router actually used**, which may differ from
  what you requested — that is the whole point of routing. The `model` field in
  your request is treated as a hint; the router may override it.
- `usage` carries the token counts. On a **cache hit**, the answer comes back with
  no model call (the tokens are the replayed original counts).

### Python (openai SDK)

```python
from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/v1", api_key="gw_your_app_key")

resp = client.chat.completions.create(
    model="auto",
    messages=[{"role": "user", "content": "Hello in one sentence."}],
)
print(resp.choices[0].message.content)
print("served by:", resp.model)
```

Send a **second, slightly reworded** version of the same question and watch it
come back from the semantic cache (instantly, no model call).

## 5. Use it from a chat UI — Open WebUI

Chat UIs that speak the OpenAI API fill their model picker from `GET /v1/models`,
so gatewAI shows up as one connection with `auto` and every registered model.
The four checks below were run on 2026-10-12 against **Open WebUI v0.12.0** and
the stack from step 2 (default registry, all-Ollama), through Open WebUI's own
API — the model list, streamed chats and a title task its UI requests — rather
than by clicking through the UI.

Create a dedicated client key for it (step 3), then start Open WebUI next to the
stack:

```bash
docker run -d --name open-webui -p 3000:8080 \
  --add-host=host.docker.internal:host-gateway \
  -e OPENAI_API_BASE_URL=http://host.docker.internal:8080/v1 \
  -e OPENAI_API_KEY=gw_your_open_webui_key \
  -e ENABLE_OLLAMA_API=false \
  -e TASK_MODEL_EXTERNAL=qwen2.5:0.5b \
  -e WEBUI_AUTH=false \
  -v open-webui:/app/backend/data \
  ghcr.io/open-webui/open-webui:v0.12.0
```

- `--add-host=host.docker.internal:host-gateway` lets the container reach the
  gateway's published port 8080 on Linux (Docker Desktop resolves the name anyway).
- `ENABLE_OLLAMA_API=false`: Open WebUI would otherwise look for an Ollama of its
  own. All traffic should go through gatewAI.
- `TASK_MODEL_EXTERNAL`: Open WebUI also sends background requests — a chat
  title, tags, follow-up suggestions — through the same connection, by default on
  the model selected for the chat. They are real chat completions, routed, cached
  and accounted like any other, so they show up in the reports. Sending them to
  the smallest model keeps them cheap; set `ENABLE_TITLE_GENERATION`,
  `ENABLE_TAGS_GENERATION` and `ENABLE_FOLLOW_UP_GENERATION` to `false` to drop
  them.
- `WEBUI_AUTH=false` skips Open WebUI's own login: fine for a local, single-user
  try, not for a shared instance.
- Open WebUI saves these settings in its volume on first start; change them later
  under *Admin Panel → Settings → Connections*, not by editing the variables.

Open <http://localhost:3000> and check four things. Keep the gateway log open
to see what the router did:

```bash
docker compose -f docker-compose.yml logs -f gateway | grep -E 'Routing to|pinned'
```

1. **Models are listed.** The picker shows `auto`, `qwen2.5:0.5b`, `qwen2.5:1.5b`
   and `qwen2.5:3b` — exactly what `GET /v1/models` returns.
2. **Streaming works.** Pick `auto` and ask a question: the answer arrives token
   by token.
3. **`auto` routes.** The log shows the router's choice, e.g.
   `Routing to ollama (tier=CLOUD_ENTRY, model=qwen2.5:1.5b, …)`.
4. **A registered id pins.** Pick `qwen2.5:3b` and ask the same short question:
   the log shows `Client pinned qwen2.5:3b (tier=CLOUD_PREMIUM), skipping
   classification` — the router does not pick a smaller model. The cache keeps
   pinned and routed answers apart, so this is a miss even right after the `auto`
   turn. A question the cache already holds *for that model* comes back as a
   `Cache HIT` with no routing line at all; ask something new to see the pin.

Open WebUI labels each answer with the model picked in the UI (`auto`), not the
one that served it; the served model is in the gateway log, in the `model` field
of the response, and on the dashboard.

## 6. First request — MCP

gatewAI is also an **MCP server** at `http://localhost:8080/mcp`, secured with the
same Bearer key. An MCP-capable assistant can call its tools: `routed_chat`,
`green_report`, `carbon_intensity`.

Example Claude Desktop configuration:

```json
{
  "mcpServers": {
    "gatewai": {
      "url": "http://localhost:8080/mcp",
      "headers": { "Authorization": "Bearer gw_your_app_key" }
    }
  }
}
```

Then ask the assistant to "use routed_chat to answer …" or "show the green_report
for the last 7 days". See [`../technical/mcp.md`](../technical/mcp.md) for the tool
details.

## 7. Open the dashboard

Browse to <http://localhost:8080/>. Paste your API key, click **Test connection**,
and you get the KPIs (cost saved in your billing currency, gCO2 avoided, cache hit rate), trends, model mix,
CSRD report downloads, and — with an **admin** key — key administration and live
routing configuration. A full tour is in
[`dashboard-guide.md`](dashboard-guide.md).

## 8. Next steps

- Understand each capability: [`features.md`](features.md)
- Know the boundaries before relying on the numbers: [`limitations.md`](limitations.md)
- Why the defaults are what they are: [`functional-choices.md`](functional-choices.md)
