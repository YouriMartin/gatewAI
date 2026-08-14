package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;

/**
 * Everything one request's trace holds, as persisted (v2 batch 9).
 *
 * <p>The two decision tables are joined by correlation id, and <b>either half
 * can be missing</b> — that absence is itself the trace:
 *
 * <ul>
 *   <li>a cache <b>hit</b> short-circuits the chain, so no routing decision was
 *       ever taken;</li>
 *   <li>a request that skipped the cache (bypass not recorded, tracing switched
 *       on mid-flight) has routing and nothing else.</li>
 * </ul>
 *
 * <p>Read-only and never recomputed: this is what happened, under the rules that
 * were in force then. Anything derived from today's configuration belongs in
 * {@link DecisionExplanation} beside it, not in here.
 *
 * @param correlationId the id shared by both rows and by the carbon record
 * @param at            when the request was decided — the later of the two rows,
 *                      so a history sorts on one field
 * @param cache         the cache decision, null when none was recorded
 * @param routing       the routing decision, null on a cache hit
 */
public record TracedDecision(String correlationId, Instant at,
                             CacheDecision cache, RoutingDecision routing) {

  /**
   * Builds one from whichever halves exist.
   *
   * @throws IllegalArgumentException if both are null — a trace of nothing is a
   *                                  bug in the caller, not an empty result
   */
  public static TracedDecision of(String correlationId, CacheDecision cache,
                                  RoutingDecision routing) {
    if (cache == null && routing == null) {
      throw new IllegalArgumentException(
          "a traced decision needs at least one recorded decision");
    }
    Instant cacheAt = cache == null ? null : cache.createdAt();
    Instant routingAt = routing == null ? null : routing.createdAt();
    return new TracedDecision(correlationId, latest(cacheAt, routingAt),
        cache, routing);
  }

  private static Instant latest(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isAfter(b) ? a : b;
  }
}
