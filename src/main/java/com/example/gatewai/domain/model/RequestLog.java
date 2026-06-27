package com.example.gatewai.domain.model;

import java.time.Instant;
import java.util.UUID;

public record RequestLog(
    UUID id,
    Instant timestamp,
    String model,
    String promptHash,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    long latencyMs,
    String clientId
) {}
