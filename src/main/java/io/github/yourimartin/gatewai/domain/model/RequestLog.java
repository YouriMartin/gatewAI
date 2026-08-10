package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One completed request: what was called, what it cost and what it emitted.
 *
 * @param correlationId ingress-assigned id shared by every record produced for
 *                      the same request — the join key between this row and the
 *                      routing / cache decisions traced from v2 batch 2. Null
 *                      when the request originated outside an HTTP call.
 */
public record RequestLog(
    UUID id,
    String correlationId,
    Instant timestamp,
    String model,
    String promptHash,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    long latencyMs,
    String clientId,
    GreenMetrics green,
    boolean cacheHit
) {}
