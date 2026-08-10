package io.github.yourimartin.gatewai.domain.port.out;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;

/**
 * Records the decisions the gateway takes on the request path (v2 batch 2).
 *
 * <p>Implementations <b>must not block and must not throw</b>: the trace exists
 * to explain requests, never to fail them. A store that is down degrades to
 * "no explanation available", never to a failed completion.
 */
public interface DecisionRecorder {

  void record(RoutingDecision decision);

  void record(CacheDecision decision);

  /**
   * Drops decisions older than {@code cutoff}.
   *
   * @return how many rows were removed
   */
  int purgeOlderThan(Instant cutoff);
}
