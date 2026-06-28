package com.example.gatewai.domain.model;

/**
 * One piece of a streamed LLM response (Phase 7.5). Intermediate chunks carry a
 * {@code contentDelta}; the terminal chunk ({@code last == true}) also carries
 * the finish reason and token usage. {@code model} is the model that actually
 * served the request (the routed one). Pure domain — no reactive types here; the
 * reactive plumbing lives in the infrastructure adapters.
 */
public record LlmStreamChunk(
    String model,
    String contentDelta,
    String finishReason,
    boolean cacheHit,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    boolean last
) {}
