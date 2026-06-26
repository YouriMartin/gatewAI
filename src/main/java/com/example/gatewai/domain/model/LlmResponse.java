package com.example.gatewai.domain.model;

public record LlmResponse(
    String model,
    String content,
    String finishReason,
    int promptTokens,
    int completionTokens,
    int totalTokens
) {}
