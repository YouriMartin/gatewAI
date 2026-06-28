package com.example.gatewai.adapter.in.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Wires the gateway's {@code @Tool} methods into the Spring AI MCP server
 * (Phase 6.4). The starter's auto-configuration discovers this {@link
 * ToolCallbackProvider} and publishes its callbacks over the configured MCP
 * transport ({@code spring.ai.mcp.server.*}).
 */
@Configuration
@ImportRuntimeHints(McpNativeRuntimeHints.class)
class McpServerConfiguration {

  @Bean
  ToolCallbackProvider gatewayToolCallbackProvider(GatewayMcpTools gatewayMcpTools) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(gatewayMcpTools)
        .build();
  }
}
