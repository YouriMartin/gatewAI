# Getting started

This guide takes you from nothing to a first answer through gatewAI: deploy, get
an API key, send a request via the OpenAI SDK **and** via MCP, and open the
dashboard.

## 1. Prerequisites

- **Docker** and **Docker Compose** (enough for the plug & play mode below).
- An **Anthropic API key** (`ANTHROPIC_API_KEY`) — Claude is the default egress.

That is all. No JDK is required for the container mode; the image builds the app
(back end + dashboard) for you.

## 2. Deploy (plug & play)

```bash
git clone https://github.com/your-user/gatewAI.git
cd gatewAI

cp .env.example .env
$EDITOR .env            # set ANTHROPIC_API_KEY

docker compose -f docker-compose.yml up --build
```

This starts the full stack: the **gateway** (port 8080), **PostgreSQL + pgvector**
(cache + metrics) and **Ollama** (local embeddings). On the first start the
gateway downloads the embedding model (`nomic-embed-text`) from Ollama, so give
the health check a minute to go green.

> `compose.yaml` (infra only) is used in development mode and takes precedence
> when you run `docker compose` without `-f`; the full stack is therefore invoked
> explicitly with `-f docker-compose.yml`. See the root `README.md` for the
> development mode (`./mvnw spring-boot:run`).

Check it is up:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP", ...}
```

## 3. Get an API key

Every `/v1/**` call must carry a **Bearer API key**. On the very first start,
gatewAI creates a **bootstrap admin** client and prints its key **once** in the
logs:

```bash
docker compose -f docker-compose.yml logs gateway | grep "Admin API key"
# ... Admin API key (shown ONCE, copy it now): gw_XXXXXXXXXXXXXXXXXXXXXXXX
```

Copy that key — it is never shown again (only a hash is stored). Keys look like
`gw_` followed by a URL-safe random string.

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

## 5. First request — MCP

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

## 6. Open the dashboard

Browse to <http://localhost:8080/>. Paste your API key, click **Test connection**,
and you get the KPIs (€ saved, gCO2 avoided, cache hit rate), trends, model mix,
CSRD report downloads, and — with an **admin** key — key administration and live
routing configuration. A full tour is in
[`dashboard-guide.md`](dashboard-guide.md).

## 7. Next steps

- Understand each capability: [`features.md`](features.md)
- Know the boundaries before relying on the numbers: [`limitations.md`](limitations.md)
- Why the defaults are what they are: [`functional-choices.md`](functional-choices.md)
