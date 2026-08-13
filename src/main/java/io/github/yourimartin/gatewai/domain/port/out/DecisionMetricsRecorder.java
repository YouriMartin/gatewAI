package io.github.yourimartin.gatewai.domain.port.out;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;

/**
 * Publishes what a decision looked like to a monitoring backend (v2 batch 6).
 *
 * <p>A sibling of {@link MetricsRecorder} rather than a method on it (D9): that
 * port speaks {@link io.github.yourimartin.gatewai.domain.model.RequestLog},
 * which exists only once a request has been <b>served</b>. A cache hit never
 * reaches a routing decision, a routing decision is taken before any tokens are
 * counted, and a bypassed request produces no log at all — three shapes one
 * {@code record(RequestLog)} cannot carry.
 *
 * <p>Same contract as {@link DecisionRecorder}: implementations must never throw
 * and never block. A gateway that fails a completion because a counter could not
 * be incremented has its priorities backwards.
 *
 * <p>Deliberately fed the <b>same objects that are persisted</b>, so the series
 * in Prometheus and the rows in {@code routing_decision} can never disagree
 * about what happened — one is the aggregate of the other, not a second
 * measurement of it.
 */
public interface DecisionMetricsRecorder {

  void record(RoutingDecision decision);

  void record(CacheDecision decision);
}
