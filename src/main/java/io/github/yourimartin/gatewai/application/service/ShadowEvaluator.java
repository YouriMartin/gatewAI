package io.github.yourimartin.gatewai.application.service;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.decision.ShadowOutcome;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.port.out.LlmClient;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Decides, off the request path, what the cache and the router would have done
 * with a shadow client's request (v4 D.1), once the client has its answer.
 *
 * <p>The request is replayed through the advisor chain with the answer the client
 * received standing in for the model ({@link LlmClient#replay}): the decisions are
 * taken and recorded by the code that takes them in {@code ENFORCE}, stamped
 * {@code SHADOW} by the request's context, and on a miss the cache stores that
 * answer as it would have — so it warms up as it would in production.
 *
 * <p>Bounded: at most {@code gatewai.shadow.max-concurrent} evaluations run at
 * once, each on its own virtual thread. Past that, the evaluation is dropped and
 * counted, never queued — shadow work must not grow without limit behind the
 * traffic it observes, nor ever slow it down.
 */
@Service
final class ShadowEvaluator {

  private static final Logger LOG = LoggerFactory.getLogger(ShadowEvaluator.class);

  private final LlmClient llmClient;
  private final WouldHitAssessor assessor;
  private final MetricsRecorder metrics;
  private final Semaphore permits;
  private final Executor executor;

  @Autowired
  ShadowEvaluator(LlmClient llmClient, WouldHitAssessor assessor, MetricsRecorder metrics,
                  @Value("${gatewai.shadow.max-concurrent:16}") int maxConcurrent) {
    this(llmClient, assessor, metrics, maxConcurrent,
        task -> Thread.ofVirtual().name("shadow-evaluation").start(task));
  }

  /** Test seam: runs evaluations on {@code executor}. */
  ShadowEvaluator(LlmClient llmClient, WouldHitAssessor assessor, MetricsRecorder metrics,
                  int maxConcurrent, Executor executor) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException(
          "gatewai.shadow.max-concurrent must be at least 1, was " + maxConcurrent);
    }
    this.llmClient = llmClient;
    this.assessor = assessor;
    this.metrics = metrics;
    this.permits = new Semaphore(maxConcurrent);
    this.executor = executor;
  }

  /**
   * Schedules the evaluation of {@code request}, answered with {@code answer}.
   * Returns at once and never throws: the client's answer is already on its way.
   *
   * @param context the request's context, captured on the request thread
   * @param answer  what the client received; empty when it could not be read back,
   *                which is counted as a failed evaluation
   */
  void submit(RequestContext context, LlmRequest request, Optional<RelayedAnswer> answer) {
    if (answer.isEmpty()) {
      LOG.debug("Shadow request {}: no text answer to replay", context.traceId());
      metrics.recordShadowDecision(ShadowOutcome.FAILED);
      return;
    }
    if (!permits.tryAcquire()) {
      metrics.recordShadowDecision(ShadowOutcome.DROPPED);
      return;
    }
    try {
      executor.execute(() -> ScopedValue.where(RequestContext.CURRENT, context)
          .run(() -> evaluate(request, answer.get())));
    } catch (RuntimeException e) {
      permits.release();
      LOG.warn("Could not start the shadow evaluation of request {}: {}",
          context.traceId(), e.toString());
      metrics.recordShadowDecision(ShadowOutcome.FAILED);
    }
  }

  /** Counts a shadow request that was never evaluated: one the chain cannot honour. */
  void bypassed() {
    metrics.recordShadowDecision(ShadowOutcome.BYPASS);
  }

  private void evaluate(LlmRequest request, RelayedAnswer answer) {
    try {
      LlmResponse replayed = llmClient.replay(request, answer);
      ShadowOutcome outcome = outcome(replayed.cacheOutcome());
      metrics.recordShadowDecision(outcome);
      if (outcome == ShadowOutcome.WOULD_HIT) {
        // Would it have been right? (v4 D.2) Inside the same permit: judging is
        // shadow work too, and must stay as bounded as the replay.
        assessor.assess(RequestContext.CURRENT.get(), request, replayed.content(), answer);
      }
    } catch (RuntimeException e) {
      LOG.warn("Shadow evaluation of request {} failed: {}",
          RequestAccounting.correlationId(), e.toString());
      metrics.recordShadowDecision(ShadowOutcome.FAILED);
    } finally {
      permits.release();
    }
  }

  /** A failed lookup is served as a miss, and so counted as one. */
  static ShadowOutcome outcome(String cacheOutcome) {
    if (CacheOutcome.HIT.name().equals(cacheOutcome)) {
      return ShadowOutcome.WOULD_HIT;
    }
    if (CacheOutcome.BYPASS.name().equals(cacheOutcome)) {
      return ShadowOutcome.BYPASS;
    }
    return ShadowOutcome.MISS;
  }
}
