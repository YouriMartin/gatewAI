package io.github.yourimartin.gatewai.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.client.ClientMode;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeRubric;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeVerdict;
import io.github.yourimartin.gatewai.domain.model.shadow.WouldHitAssessment;
import io.github.yourimartin.gatewai.domain.port.out.AnswerJudge;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.EmbeddingWindow;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WouldHitAssessorTest {

  private static final String JUDGE = "judge-model";
  private static final RequestContext SHADOW =
      new RequestContext("client-1", "trace-1", ClientMode.SHADOW);
  private static final LlmRequest REQUEST =
      new LlmRequest("auto", List.of(new LlmMessage("user", "What is a B-tree?")), null, null);
  private static final RelayedAnswer RECEIVED =
      new RelayedAnswer("m", "A balanced search tree.", "stop", null);
  private static final String CACHED = "A self-balancing tree.";

  private final TextEmbedder embedder = mock(TextEmbedder.class);
  private final EmbeddingWindow window = mock(EmbeddingWindow.class);
  private final AnswerJudge judge = mock(AnswerJudge.class);
  private final DecisionRecorder recorder = mock(DecisionRecorder.class);
  private final MetricsRecorder metrics = mock(MetricsRecorder.class);
  private final ModelRegistry registry = mock(ModelRegistry.class);
  private final RequestAccounting accounting = mock(RequestAccounting.class);
  private final Deque<Double> draws = new ArrayDeque<>();

  @BeforeEach
  void setUp() {
    when(embedder.embed(RECEIVED.text())).thenReturn(new float[] {1, 0});
    when(embedder.embed(CACHED)).thenReturn(new float[] {1, 1});
    when(embedder.modelId()).thenReturn("embedding-model");
    when(window.fits(anyString())).thenReturn(true);
    when(registry.findByModelId(JUDGE)).thenReturn(Optional.of(new ModelDefinition(
        "judge", "openai", JUDGE, ModelPricing.NONE, null, ModelTier.CLOUD_ENTRY)));
  }

  @Test
  void everyWouldHitIsComparedBySimilarityAndTheJudgeStaysOffByDefault() {
    assessor(0, null).assess(SHADOW, REQUEST, CACHED, RECEIVED);

    WouldHitAssessment assessment = recorded();
    assertThat(assessment.answerSimilarity()).isCloseTo(Math.sqrt(0.5),
        org.assertj.core.api.Assertions.within(1e-6));
    assertThat(assessment.embeddingModel()).isEqualTo("embedding-model");
    assertThat(assessment.beyondWindow()).isFalse();
    assertThat(assessment.judgeOutcome()).isEqualTo(JudgeOutcome.NOT_SAMPLED);
    assertThat(assessment.judgeModel()).isNull();
    assertThat(assessment.correlationId()).isEqualTo("trace-1");
    assertThat(assessment.clientId()).isEqualTo("client-1");
    verifyNoInteractions(judge, accounting);
  }

  @Test
  void onlyTheSampledShareGoesToTheJudge() {
    when(judge.judge(eq(JUDGE), any())).thenReturn(new AnswerJudge.Reply("YES", TokenUsage.NONE));
    WouldHitAssessor assessor = assessor(0.25, JUDGE);
    draws.add(0.10);
    draws.add(0.25);
    draws.add(0.90);

    for (int i = 0; i < 3; i++) {
      assessor.assess(SHADOW, REQUEST, CACHED, RECEIVED);
    }

    ArgumentCaptor<WouldHitAssessment> all = ArgumentCaptor.forClass(WouldHitAssessment.class);
    verify(recorder, org.mockito.Mockito.times(3)).record(all.capture());
    assertThat(all.getAllValues()).extracting(WouldHitAssessment::judgeOutcome)
        .containsExactly(JudgeOutcome.JUDGED, JudgeOutcome.NOT_SAMPLED, JudgeOutcome.NOT_SAMPLED);
  }

  @Test
  void aJudgedWouldHitRecordsItsVerdictJudgeAndRubricAndIsAccountedUnderTheSystemClient() {
    when(judge.judge(eq(JUDGE), any()))
        .thenReturn(new AnswerJudge.Reply("No.", new TokenUsage(120, 1, 121)));
    draws.add(0.0);

    assessor(1, JUDGE).assess(SHADOW, REQUEST, CACHED, RECEIVED);

    WouldHitAssessment assessment = recorded();
    assertThat(assessment.judgeOutcome()).isEqualTo(JudgeOutcome.JUDGED);
    assertThat(assessment.verdict()).isEqualTo(JudgeVerdict.NO);
    assertThat(assessment.judgeModel()).isEqualTo(JUDGE);
    assertThat(assessment.rubricVersion()).isEqualTo(JudgeRubric.VERSION);
    assertThat(assessment.judgeCorrelationId()).isNotNull().isNotEqualTo("trace-1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<LlmMessage>> prompt = ArgumentCaptor.forClass(List.class);
    verify(judge).judge(eq(JUDGE), prompt.capture());
    assertThat(prompt.getValue().get(1).content()).contains(RECEIVED.text()).contains(CACHED);
    verify(accounting).record(any(LlmRequest.class), eq(JUDGE), eq(new TokenUsage(120, 1, 121)),
        eq(false), anyLong(), eq(assessment.judgeCorrelationId()),
        eq(WouldHitAssessor.JUDGE_CLIENT_ID));
    verify(metrics).recordShadowJudgement(JudgeOutcome.JUDGED, JudgeVerdict.NO);
  }

  @Test
  void aFailedJudgeCallIsRecordedAsSuchWithNoVerdictAndNothingAccounted() {
    when(judge.judge(eq(JUDGE), any())).thenThrow(new IllegalStateException("timeout"));
    draws.add(0.0);

    assessor(1, JUDGE).assess(SHADOW, REQUEST, CACHED, RECEIVED);

    WouldHitAssessment assessment = recorded();
    assertThat(assessment.judgeOutcome()).isEqualTo(JudgeOutcome.FAILED);
    assertThat(assessment.verdict()).isNull();
    assertThat(assessment.judgeCorrelationId()).isNull();
    verify(accounting, never()).record(any(), anyString(), any(), anyBoolean(), anyLong(), any(),
        any());
  }

  @Test
  void anAnswerPastTheEmbeddingWindowIsFlaggedAndAFailedEmbeddingLeavesNoSimilarity() {
    when(window.fits(CACHED)).thenReturn(false);
    when(embedder.embed(CACHED)).thenThrow(new IllegalStateException("onnx"));

    assessor(0, null).assess(SHADOW, REQUEST, CACHED, RECEIVED);

    WouldHitAssessment assessment = recorded();
    assertThat(assessment.beyondWindow()).isTrue();
    assertThat(assessment.answerSimilarity()).isNull();
    assertThat(assessment.embeddingModel()).isNull();
  }

  @Test
  void neverThrowsEvenWhenTheRecorderDoes() {
    org.mockito.Mockito.doThrow(new IllegalStateException("db"))
        .when(recorder).record(any(WouldHitAssessment.class));

    assessor(0, null).assess(SHADOW, REQUEST, CACHED, RECEIVED);
  }

  @Test
  void aSampleRateWithoutARegisteredJudgeFailsStartup() {
    assertThatThrownBy(() -> assessor(0.1, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gatewai.shadow.judge.model");
    assertThatThrownBy(() -> assessor(0.1, "not-registered"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not-registered");
    assertThatThrownBy(() -> assessor(1.5, JUDGE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("between 0 and 1");
  }

  private WouldHitAssessor assessor(double rate, String judgeModel) {
    return new WouldHitAssessor(embedder, window, judge, recorder, metrics, registry, accounting,
        rate, judgeModel, () -> draws.isEmpty() ? 1.0 : draws.poll());
  }

  private WouldHitAssessment recorded() {
    ArgumentCaptor<WouldHitAssessment> assessment =
        ArgumentCaptor.forClass(WouldHitAssessment.class);
    verify(recorder).record(assessment.capture());
    return assessment.getValue();
  }
}
