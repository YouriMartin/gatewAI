# MCP exposure (Phase 6.4)

The gateway exposes itself as an **MCP server** (Model Context Protocol) on top of
its OpenAI REST ingress. An MCP-capable assistant (Claude Desktop, IDE, agent) can
thus drive the proxy: complete a **routed/cached/accounted** prompt and read the
cost + carbon footprint — all without going through a third party.

## Architecture

MCP is a **second ingress**, treated like the OpenAI web one: an inbound adapter
`io.github.yourimartin.gatewai.adapter.in.mcp` that holds no business logic and reuses the
existing `in`/`out` ports. The green advisor chain (cache → router → accounting)
stays the single source of truth.

```
adapter/in/mcp
├── GatewayMcpTools          # @Tool methods (MCP ingress)
├── McpServerConfiguration   # ToolCallbackProvider + native hints
├── *ToolResult              # exposed records (tools' JSON schema)
└── McpNativeRuntimeHints    # GraalVM reflection hints
```

- Dependency: `spring-ai-starter-mcp-server-webmvc` (streamable-HTTP transport
  reusing Spring MVC, no separate server).
- The `@Tool`s are published via a `ToolCallbackProvider` bean
  (`MethodToolCallbackProvider`) discovered by the starter's auto-configuration.
- Validated by `ArchitectureTest` (ArchUnit) as an onion-layer adapter.

## Exposed tools

| Tool | Role | Underlying port |
|---|---|---|
| `routed_chat` | Completes a prompt through the gateway; returns the answer, **the model actually selected**, the cache hit and the tokens | `ChatCompletionUseCase` |
| `green_report` | Cost (€) and footprint (gCO2) aggregated over an ISO-8601 range (default 30 days), + avoided cost/CO2, hit rate, model mix | `GenerateGreenReportUseCase` |
| `carbon_intensity` | Current grid carbon intensity (gCO2-eq/kWh) for a zone | `CarbonIntensityProvider` |

## Configuration

`application.properties`:

```properties
spring.ai.mcp.server.enabled=true
spring.ai.mcp.server.name=gatewai-green-proxy
spring.ai.mcp.server.version=0.1.0
spring.ai.mcp.server.instructions=...
```

Set `spring.ai.mcp.server.enabled=false` to fully disable the MCP exposure.

## Security

The `/mcp` endpoint is protected by the **same** `ApiKeyAuthenticationFilter` as
`/v1/**`: the MCP client sends `Authorization: Bearer <gatewai API key>`. A valid
key also binds the `RequestContext` (clientId), so green accounting and cache
namespacing work identically over the MCP channel. Consistent with the project's
on-premise zero-transit posture.

## Client example (Claude Desktop)

```json
{
  "mcpServers": {
    "gatewai": {
      "url": "http://localhost:8080/mcp",
      "headers": { "Authorization": "Bearer <your-gatewai-API-key>" }
    }
  }
}
```

## Native image (6.3)

The tool result records are (de)serialized by reflection by MCP:
`McpNativeRuntimeHints` registers their binding hints for GraalVM, mirroring
`NativeRuntimeHints` on the web side.
