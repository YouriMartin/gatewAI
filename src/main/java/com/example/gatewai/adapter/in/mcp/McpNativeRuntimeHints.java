package com.example.gatewai.adapter.in.mcp;

import java.util.List;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers Jackson binding reflection hints for the MCP tool result records so
 * they serialize under GraalVM native image (Phase 6.3/6.4). MCP serializes
 * tool outputs reflectively, so the records must be reachable in the AOT graph.
 */
class McpNativeRuntimeHints implements RuntimeHintsRegistrar {

  private static final List<Class<?>> BOUND_TYPES = List.of(
      GreenReportToolResult.class,
      CarbonIntensityToolResult.class,
      RoutedChatToolResult.class);

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    BindingReflectionHintsRegistrar binding = new BindingReflectionHintsRegistrar();
    for (Class<?> type : BOUND_TYPES) {
      binding.registerReflectionHints(hints.reflection(), type);
    }
  }
}
