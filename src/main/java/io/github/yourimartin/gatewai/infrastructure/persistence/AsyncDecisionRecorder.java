package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes decisions off the request path (v2 batch 2).
 *
 * <p>Two guarantees, both load-bearing: the write never blocks the caller, and
 * it never throws. Explaining a request must not be able to break it — so a
 * database that is down costs the trace, not the completion. Failures are
 * logged (once per occurrence, at warn) and counted as
 * {@code gatewai.decisions.write.failures} so the silence is visible in Grafana
 * instead of being genuinely silent.
 *
 * <p>The executor is Boot's application task executor, which is virtual-thread
 * backed here ({@code spring.threads.virtual.enabled=true}), so a slow insert
 * parks a virtual thread rather than occupying a pooled platform one.
 */
@Component
class AsyncDecisionRecorder implements DecisionRecorder {

  private static final Logger LOG =
      LoggerFactory.getLogger(AsyncDecisionRecorder.class);

  private final SpringDataRoutingDecisionRepository routingRepository;
  private final SpringDataCacheDecisionRepository cacheRepository;
  private final Executor executor;
  private final MeterRegistry meterRegistry;
  private final DecisionRecordingProperties properties;

  AsyncDecisionRecorder(
      SpringDataRoutingDecisionRepository routingRepository,
      SpringDataCacheDecisionRepository cacheRepository,
      @Qualifier("applicationTaskExecutor") TaskExecutor executor,
      MeterRegistry meterRegistry,
      DecisionRecordingProperties properties) {
    this.routingRepository = routingRepository;
    this.cacheRepository = cacheRepository;
    this.executor = executor;
    this.meterRegistry = meterRegistry;
    this.properties = properties;
  }

  @Override
  public void record(RoutingDecision decision) {
    submit("routing", () -> routingRepository.save(
        new RoutingDecisionEntity(decision)));
  }

  @Override
  public void record(CacheDecision decision) {
    submit("cache", () -> cacheRepository.save(
        new CacheDecisionEntity(decision)));
  }

  @Override
  @Transactional
  public int purgeOlderThan(Instant cutoff) {
    return routingRepository.deleteByCreatedAtBefore(cutoff)
        + cacheRepository.deleteByCreatedAtBefore(cutoff);
  }

  /**
   * Hands the write to the executor, swallowing everything — including a
   * rejected submission, which is itself a failure to record and must not
   * surface to the request thread.
   */
  private void submit(String kind, Runnable write) {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      executor.execute(() -> {
        try {
          write.run();
        } catch (RuntimeException e) {
          failed(kind, e);
        }
      });
    } catch (RuntimeException e) {
      failed(kind, e);
    }
  }

  private void failed(String kind, RuntimeException e) {
    meterRegistry.counter("gatewai.decisions.write.failures",
        "kind", kind).increment();
    LOG.warn("Could not record {} decision: {}", kind, e.toString());
  }

  /** Test seam: exposes the counter without reaching into Micrometer. */
  double failureCount(String kind) {
    return counterValue(() -> meterRegistry
        .find("gatewai.decisions.write.failures")
        .tag("kind", kind)
        .counter());
  }

  private static double counterValue(
      Supplier<io.micrometer.core.instrument.Counter> counter) {
    var found = counter.get();
    return found == null ? 0 : found.count();
  }
}
