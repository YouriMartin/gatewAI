package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One completed request: what was called, what it cost, what it emitted, and how
 * that figure was arrived at.
 *
 * @param correlationId ingress-assigned id shared by every record produced for
 *                      the same request — the join key between this row and the
 *                      routing / cache decisions traced from v2 batch 2. Null
 *                      when the request originated outside an HTTP call.
 * @param provenance    the grid, the labels and the assumptions behind
 *                      {@code green} (v3 lot C.5); {@code null} is read as
 *                      {@link GreenProvenance#UNKNOWN}, which is also how rows
 *                      written before C.5 come back
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
    GreenProvenance provenance,
    boolean cacheHit
) {

  public RequestLog {
    if (provenance == null) {
      provenance = GreenProvenance.UNKNOWN;
    }
  }
}
