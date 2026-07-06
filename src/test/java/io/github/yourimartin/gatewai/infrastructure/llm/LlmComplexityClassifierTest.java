package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourimartin.gatewai.domain.model.ModelTier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class LlmComplexityClassifierTest {

  private ChatClient chatClient;
  private ChatClient.ChatClientRequestSpec requestSpec;
  private ChatClient.CallResponseSpec responseSpec;
  private ClassifierProperties properties;
  private HeuristicComplexityClassifier heuristic;
  private LlmComplexityClassifier classifier;

  @BeforeEach
  void setUp() {
    chatClient = mock(ChatClient.class);
    requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    responseSpec = mock(ChatClient.CallResponseSpec.class);
    heuristic = mock(HeuristicComplexityClassifier.class);

    lenient().when(chatClient.prompt()).thenReturn(requestSpec);
    lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
    lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
    lenient().when(requestSpec.call()).thenReturn(responseSpec);

    properties = new ClassifierProperties();
    properties.setStrategy(ClassifierStrategy.LLM);

    classifier = new LlmComplexityClassifier(chatClient, properties, heuristic);
  }

  // ---- Trivial input short-circuits without calling the model ----

  @Test
  void nullTextReturnsLocalWithoutCallingModel() {
    assertEquals(ModelTier.LOCAL, classifier.classify(null));
    verify(chatClient, never()).prompt();
  }

  @Test
  void blankTextReturnsLocalWithoutCallingModel() {
    assertEquals(ModelTier.LOCAL, classifier.classify("   "));
    verify(chatClient, never()).prompt();
  }

  // ---- LLM strategy ----

  @Test
  void usesTierFromStructuredOutput() {
    stubModel(new ClassificationResult(ModelTier.CLOUD_PREMIUM, "complex"));

    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Refactor this service"));
  }

  @Test
  void entryTierFromStructuredOutput() {
    stubModel(new ClassificationResult(ModelTier.CLOUD_ENTRY, "moderate"));

    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify("Summarize this"));
    verify(heuristic, never()).classify(anyString());
  }

  @Test
  void sendsConfiguredSystemPromptAndUserText() {
    properties.setSystemPrompt("custom rules");
    stubModel(new ClassificationResult(ModelTier.LOCAL, "trivial"));

    classifier.classify("Hello there");

    verify(requestSpec).system("custom rules");
    verify(requestSpec).user("Hello there");
  }

  // ---- HEURISTIC strategy delegates ----

  @Test
  void heuristicStrategyDelegatesWithoutCallingModel() {
    properties.setStrategy(ClassifierStrategy.HEURISTIC);
    when(heuristic.classify("Refactor")).thenReturn(ModelTier.CLOUD_PREMIUM);

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("Refactor"));
    verify(chatClient, never()).prompt();
  }

  // ---- Fallback on failure ----

  @Test
  void fallsBackToHeuristicWhenModelThrows() {
    when(responseSpec.entity(ClassificationResult.class))
        .thenThrow(new RuntimeException("model unreachable"));
    when(heuristic.classify("Refactor")).thenReturn(ModelTier.CLOUD_PREMIUM);

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("Refactor"));
  }

  @Test
  void fallsBackToHeuristicWhenTierIsNull() {
    stubModel(new ClassificationResult(null, "unsure"));
    when(heuristic.classify("Refactor")).thenReturn(ModelTier.CLOUD_PREMIUM);

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("Refactor"));
  }

  @Test
  void fallsBackToHeuristicWhenResultIsNull() {
    stubModel(null);
    when(heuristic.classify("Hi")).thenReturn(ModelTier.LOCAL);

    assertEquals(ModelTier.LOCAL, classifier.classify("Hi"));
  }

  @Test
  void routesToPremiumWhenFallbackDisabledAndModelFails() {
    properties.setFallbackToHeuristic(false);
    when(responseSpec.entity(ClassificationResult.class))
        .thenThrow(new RuntimeException("boom"));

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("anything"));
    verify(heuristic, never()).classify(anyString());
  }

  private void stubModel(ClassificationResult result) {
    when(responseSpec.entity(ClassificationResult.class)).thenReturn(result);
  }
}
