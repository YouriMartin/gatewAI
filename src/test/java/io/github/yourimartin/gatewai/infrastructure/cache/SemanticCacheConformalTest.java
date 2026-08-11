package io.github.yourimartin.gatewai.infrastructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ConformalStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * The cache's conformal prediction set (v2 batch 3).
 *
 * <p>Kept apart from {@link SemanticCacheAdvisorTest}, which covers the advisor's
 * caching behaviour: what is under test here is the <b>decision rule</b> — which
 * threshold is in force, and what the size of the resulting set means.
 */
@ExtendWith(MockitoExtension.class)
class SemanticCacheConformalTest {

  @Mock
  private VectorStore vectorStore;

  @Mock
  private CallAdvisorChain callChain;

  @Mock
  private CacheDecisionTracer tracer;

  private SemanticCacheProperties properties;
  private SemanticCacheAdvisor advisor;

  @BeforeEach
  void setUp() {
    properties = new SemanticCacheProperties();
    advisor = new SemanticCacheAdvisor(vectorStore, properties, tracer,
        CalibrationFixtures.none(properties::getSimilarityThreshold));
  }

  // ---- Conformal prediction set (v2 batch 3) ----

  @Test
  @DisplayName("a calibrated singleton set is served, at the calibrated threshold")
  void aSingletonPredictionSetIsServed() {
    advisor = calibratedAdvisor(0.90);
    ChatClientRequest request = buildRequest("What is Spring?");
    // 0.91 clears the calibrated 0.90 but would have missed the fixed 0.92.
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(cached("What is Spring?", "Spring is a framework.", 0.91),
            cached("Something else", "Another answer", 0.40)));

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    verify(callChain, never()).nextCall(any());
    assertEquals("Spring is a framework.",
        result.chatResponse().getResult().getOutput().getText());
    verify(tracer).decided(any(), any(), any(), eq(0.90),
        eq(ConformalStatus.SINGLETON));
  }

  @Test
  @DisplayName("two plausible answers are a risk signal, not a tie to break")
  void anAmbiguousPredictionSetIsNotServed() {
    advisor = calibratedAdvisor(0.90);
    ChatClientRequest request = buildRequest("What is Spring?");
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(cached("What is Spring?", "A framework.", 0.97),
            cached("What is Spring Boot?", "A different thing.", 0.93)));
    when(callChain.nextCall(any()))
        .thenReturn(buildLlmResponse("fresh answer", "model", "stop"));

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(any());
    verify(tracer).decided(any(), any(), isNull(), eq(0.90),
        eq(ConformalStatus.AMBIGUOUS));
  }

  @Test
  @DisplayName("without a calibration the previous behaviour is untouched")
  void uncalibratedKeepsTheFixedThresholdAndTheBestCandidate() {
    ChatClientRequest request = buildRequest("What is Spring?");
    // Two candidates above the fixed 0.92: the best one is served, as before.
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(cached("What is Spring?", "A framework.", 0.97),
            cached("What is Spring Boot?", "A different thing.", 0.93)));

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    verify(callChain, never()).nextCall(any());
    assertEquals("A framework.",
        result.chatResponse().getResult().getOutput().getText());
    verify(tracer).decided(any(), any(), any(), eq(0.92),
        eq(ConformalStatus.NOT_CALIBRATED));
  }

  @Test
  @DisplayName("a stale calibration falls back to the fixed threshold and says so")
  void staleCalibrationDegradesToTheFixedThreshold() {
    advisor = new SemanticCacheAdvisor(vectorStore, properties, tracer,
        CalibrationFixtures.stale(
            CalibrationFixtures.calibration(CalibrationTarget.CACHE, 0.80),
            properties.getSimilarityThreshold()));
    ChatClientRequest request = buildRequest("What is Spring?");
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(cached("What is Spring?", "A framework.", 0.85)));
    when(callChain.nextCall(any()))
        .thenReturn(buildLlmResponse("fresh answer", "model", "stop"));

    advisor.adviseCall(request, callChain);

    // 0.85 would have cleared the stale 0.80 but not the fixed 0.92.
    verify(callChain).nextCall(any());
    verify(tracer).decided(any(), any(), isNull(), eq(0.92),
        eq(ConformalStatus.STALE_CALIBRATION));
  }

  private SemanticCacheAdvisor calibratedAdvisor(double threshold) {
    return new SemanticCacheAdvisor(vectorStore, properties, tracer,
        CalibrationFixtures.applied(
            CalibrationFixtures.calibration(CalibrationTarget.CACHE, threshold),
            properties.getSimilarityThreshold()));
  }

  private static Document cached(String question, String answer, double score) {
    return scored(new Document(question, Map.of(
        SemanticCacheAdvisor.CACHE_RESPONSE_KEY, answer)), score);
  }


  private static ChatClientRequest buildRequest(String userText) {
    return ChatClientRequest.builder()
        .prompt(new Prompt(new UserMessage(userText)))
        .context(Map.of())
        .build();
  }

  private static Document scored(Document document, double score) {
    return Document.builder()
        .id(document.getId())
        .text(document.getText())
        .metadata(document.getMetadata())
        .score(score)
        .build();
  }

  private static ChatClientResponse buildLlmResponse(String text, String model,
                                                     String finishReason) {
    Generation generation = new Generation(new AssistantMessage(text),
        ChatGenerationMetadata.builder().finishReason(finishReason).build());
    ChatResponseMetadata meta = ChatResponseMetadata.builder()
        .model(model).usage(new DefaultUsage(10, 5)).build();
    return ChatClientResponse.builder()
        .chatResponse(new ChatResponse(List.of(generation), meta))
        .context(Map.of())
        .build();
  }
}
