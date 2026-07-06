/**
 * Inbound MCP adapter (Phase 6.4): exposes the gateway as a Model Context
 * Protocol server. A second ingress alongside the OpenAI REST adapter — it
 * speaks MCP to assistants (Claude Desktop, IDEs) while reusing the very same
 * inbound use cases and outbound ports, so the green routing/caching/accounting
 * chain is unchanged.
 *
 * <p>Tools are plain {@code @Tool}-annotated methods on {@link
 * io.github.yourimartin.gatewai.adapter.in.mcp.GatewayMcpTools}, surfaced to MCP clients
 * by the {@link org.springframework.ai.tool.ToolCallbackProvider} bean declared
 * in {@link io.github.yourimartin.gatewai.adapter.in.mcp.McpServerConfiguration}.
 */
package io.github.yourimartin.gatewai.adapter.in.mcp;
