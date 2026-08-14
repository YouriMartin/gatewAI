package io.github.yourimartin.gatewai.domain.port.out;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.TracedDecision;

/**
 * Reads back the decisions {@link DecisionRecorder} wrote (v2 batch 9).
 *
 * <p>A separate port from the recorder, and not for symmetry: the two have
 * opposite failure contracts. Writing must never block and never throw, because
 * a trace must not be able to break a completion. Reading is an admin request
 * that produced nothing useful if it failed, so it is synchronous and **is**
 * allowed to throw — an operator asking why is owed the error.
 *
 * <p>Both methods return what was persisted, with no recomputation whatsoever.
 */
public interface DecisionHistory {

  /**
   * The trace of one request, cache and routing halves together.
   *
   * @return empty when nothing was recorded under that id — which also happens
   *         when decision recording is switched off
   */
  Optional<TracedDecision> byCorrelationId(String correlationId);

  /**
   * The most recent traces, newest first.
   *
   * <p>Merged across both tables rather than taken from the routing one: a cache
   * hit never reaches the router, so a history built on routing rows alone would
   * silently omit exactly the requests the cache answered.
   *
   * @param limit maximum number of requests to return
   */
  List<TracedDecision> recent(int limit);
}
