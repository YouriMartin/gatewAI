package io.github.yourimartin.gatewai.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.github.yourimartin.gatewai.domain.model.client.ClientMode;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.ShadowOutcome;
import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.port.out.LlmClient;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;

import org.junit.jupiter.api.Test;

class ShadowEvaluatorTest {

  private static final RequestContext SHADOW =
      new RequestContext("client-1", "trace-1", ClientMode.SHADOW);
  private static final LlmRequest REQUEST =
      new LlmRequest("auto", List.of(new LlmMessage("user", "Hi")), null, null);
  private static final RelayedAnswer ANSWER = new RelayedAnswer("m", "Hello", "stop", null);

  private final LlmClient llmClient = mock(LlmClient.class);
  private final MetricsRecorder metrics = mock(MetricsRecorder.class);
  private final WouldHitAssessor assessor = mock(WouldHitAssessor.class);

  @Test
  void replaysUnderTheRequestsContextAndCountsTheCacheOutcome() {
    AtomicReference<RequestContext> seen = new AtomicReference<>();
    when(llmClient.replay(REQUEST, ANSWER)).thenAnswer(invocation -> {
      seen.set(RequestContext.CURRENT.get());
      return response("HIT");
    });

    new ShadowEvaluator(llmClient, assessor, metrics, 1, Runnable::run)
        .submit(SHADOW, REQUEST, Optional.of(ANSWER));

    assertThat(seen.get()).isEqualTo(SHADOW);
    verify(metrics).recordShadowDecision(ShadowOutcome.WOULD_HIT);
  }

  @Test
  void dropsAndCountsWhatExceedsTheConcurrencyLimitInsteadOfQueueingIt() {
    List<Runnable> running = new ArrayList<>();
    ShadowEvaluator evaluator = new ShadowEvaluator(llmClient, assessor, metrics, 1, running::add);

    evaluator.submit(SHADOW, REQUEST, Optional.of(ANSWER));
    evaluator.submit(SHADOW, REQUEST, Optional.of(ANSWER));

    assertThat(running).hasSize(1);
    verify(metrics).recordShadowDecision(ShadowOutcome.DROPPED);

    // Once the first one ends, its permit is free again.
    when(llmClient.replay(any(), any())).thenReturn(response("MISS"));
    running.getFirst().run();
    evaluator.submit(SHADOW, REQUEST, Optional.of(ANSWER));
    assertThat(running).hasSize(2);
  }

  @Test
  void aFailedReplayIsCountedAndReleasesItsPermit() {
    List<Runnable> running = new ArrayList<>();
    ShadowEvaluator evaluator = new ShadowEvaluator(llmClient, assessor, metrics, 1, running::add);
    when(llmClient.replay(any(), any())).thenThrow(new IllegalStateException("boom"));

    evaluator.submit(SHADOW, REQUEST, Optional.of(ANSWER));
    running.getFirst().run();
    evaluator.submit(SHADOW, REQUEST, Optional.of(ANSWER));

    verify(metrics).recordShadowDecision(ShadowOutcome.FAILED);
    assertThat(running).hasSize(2);
  }

  @Test
  void anExecutorThatRefusesReleasesThePermitAndNeverThrows() {
    ShadowEvaluator evaluator = new ShadowEvaluator(llmClient, assessor, metrics, 1, task -> {
      throw new java.util.concurrent.RejectedExecutionException("full");
    });

    evaluator.submit(SHADOW, REQUEST, Optional.of(ANSWER));
    evaluator.submit(SHADOW, REQUEST, Optional.of(ANSWER));

    verify(metrics, org.mockito.Mockito.times(2)).recordShadowDecision(ShadowOutcome.FAILED);
  }

  @Test
  void anAnswerThatCouldNotBeReadBackIsAFailedEvaluation() {
    new ShadowEvaluator(llmClient, assessor, metrics, 1, Runnable::run)
        .submit(SHADOW, REQUEST, Optional.empty());

    verify(metrics).recordShadowDecision(ShadowOutcome.FAILED);
    verifyNoInteractions(llmClient);
  }

  @Test
  void mapsTheCacheOutcomes() {
    assertThat(ShadowEvaluator.outcome("HIT")).isEqualTo(ShadowOutcome.WOULD_HIT);
    assertThat(ShadowEvaluator.outcome("MISS")).isEqualTo(ShadowOutcome.MISS);
    assertThat(ShadowEvaluator.outcome("BYPASS")).isEqualTo(ShadowOutcome.BYPASS);
    // A failed lookup is served as a miss.
    assertThat(ShadowEvaluator.outcome("ERROR")).isEqualTo(ShadowOutcome.MISS);
    assertThat(ShadowEvaluator.outcome(null)).isEqualTo(ShadowOutcome.MISS);
  }

  @Test
  void refusesANonPositiveLimit() {
    assertThatThrownBy(() -> new ShadowEvaluator(llmClient, assessor, metrics, 0, Runnable::run))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gatewai.shadow.max-concurrent");
  }

  private static LlmResponse response(String cacheOutcome) {
    return new LlmResponse("m", "Hello", "stop", 0, 0, 0, "HIT".equals(cacheOutcome),
        cacheOutcome, null);
  }

  @Test
  void aWouldHitIsAssessedWithTheCachedAnswerAndAMissIsNot() {
    when(llmClient.replay(REQUEST, ANSWER)).thenReturn(response("HIT"));
    ShadowEvaluator evaluator = new ShadowEvaluator(llmClient, assessor, metrics, 1, Runnable::run);

    evaluator.submit(SHADOW, REQUEST, Optional.of(ANSWER));

    verify(assessor).assess(SHADOW, REQUEST, "Hello", ANSWER);

    org.mockito.Mockito.reset(assessor);
    when(llmClient.replay(REQUEST, ANSWER)).thenReturn(response("MISS"));
    evaluator.submit(SHADOW, REQUEST, Optional.of(ANSWER));
    verifyNoInteractions(assessor);
  }
}
