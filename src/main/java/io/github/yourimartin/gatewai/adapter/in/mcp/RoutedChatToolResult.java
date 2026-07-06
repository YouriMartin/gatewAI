package io.github.yourimartin.gatewai.adapter.in.mcp;

import io.github.yourimartin.gatewai.domain.model.LlmResponse;

/**
 * MCP-facing result of a completion routed through the gateway. Surfaces not
 * just the answer but the gateway's value-add: which model actually served it,
 * whether the semantic cache short-circuited the call, and token usage.
 *
 * @param content          the completion text
 * @param model            the model the router actually selected
 * @param finishReason     provider finish reason (may be {@code null})
 * @param cacheHit         {@code true} when served from the semantic cache
 * @param promptTokens     prompt tokens (replayed values on a cache hit)
 * @param completionTokens completion tokens
 * @param totalTokens      total tokens
 */
record RoutedChatToolResult(
    String content,
    String model,
    String finishReason,
    boolean cacheHit,
    int promptTokens,
    int completionTokens,
    int totalTokens) {

  static RoutedChatToolResult from(LlmResponse response) {
    return new RoutedChatToolResult(
        response.content(),
        response.model(),
        response.finishReason(),
        response.cacheHit(),
        response.promptTokens(),
        response.completionTokens(),
        response.totalTokens());
  }
}
