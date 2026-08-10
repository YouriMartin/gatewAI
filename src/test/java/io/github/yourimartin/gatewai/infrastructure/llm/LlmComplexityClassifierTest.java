package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.github.yourimartin.gatewai.infrastructure.llm.ClassificationOutcomeFixtures.outcome;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
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
    properties.setModelId("classifier-model");

    // Blank input is the heuristic's shared short-circuit for every strategy.
    lenient().when(heuristic.classify(null)).thenReturn(blank());
    lenient().when(heuristic.classify("   ")).thenReturn(blank());

    classifier = new LlmComplexityClassifier(
        chatClient, properties, new ModelRegistryProperties(), heuristic);
  }

  private static ClassificationOutcome blank() {
    return new ClassificationOutcome(ModelTier.LOCAL,
        ClassificationJustification.Heuristic.of(
            ClassificationJustification.HeuristicRule.BLANK_TEXT));
  }

  // ---- Trivial input short-circuits without calling the model ----

  @Test
  void nullTextReturnsLocalWithoutCallingModel() {
    assertEquals(ModelTier.LOCAL, classifier.classify(null).tier());
    verify(chatClient, never()).prompt();
  }

  @Test
  void blankTextReturnsLocalWithoutCallingModel() {
    assertEquals(ModelTier.LOCAL, classifier.classify("   ").tier());
    verify(chatClient, never()).prompt();
  }

  // ---- LLM strategy ----

  @Test
  void usesTierFromStructuredOutput() {
    stubModel(new ClassificationResult(ModelTier.CLOUD_PREMIUM, "complex"));

    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Refactor this service").tier());
  }

  @Test
  void entryTierFromStructuredOutput() {
    stubModel(new ClassificationResult(ModelTier.CLOUD_ENTRY, "moderate"));

    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify("Summarize this").tier());
    verify(heuristic, never()).classify(anyString());
  }

  @Test
  void sendsConfiguredSystemPromptAndUserText() {
    properties.setSystemPrompt("custom rules");
    stubModel(new ClassificationResult(ModelTier.LOCAL, "trivial"));

    classifier.classify("Hello there").tier();

    verify(requestSpec).system("custom rules");
    verify(requestSpec).user("Hello there");
  }

  // ---- Fallback on failure ----

  @Test
  void fallsBackToHeuristicWhenModelThrows() {
    when(responseSpec.entity(ClassificationResult.class))
        .thenThrow(new RuntimeException("model unreachable"));
    when(heuristic.classify("Refactor")).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("Refactor").tier());
  }

  @Test
  void fallsBackToHeuristicWhenTierIsNull() {
    stubModel(new ClassificationResult(null, "unsure"));
    when(heuristic.classify("Refactor")).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("Refactor").tier());
  }

  @Test
  void fallsBackToHeuristicWhenResultIsNull() {
    stubModel(null);
    when(heuristic.classify("Hi")).thenReturn(outcome(ModelTier.LOCAL));

    assertEquals(ModelTier.LOCAL, classifier.classify("Hi").tier());
  }

  @Test
  void routesToPremiumWhenFallbackDisabledAndModelFails() {
    properties.setFallbackToHeuristic(false);
    when(responseSpec.entity(ClassificationResult.class))
        .thenThrow(new RuntimeException("boom"));

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("anything").tier());
    verify(heuristic, never()).classify(anyString());
  }

  // ---- Justification (v2 batch 1) ----

  @Test
  void justificationCarriesTheModelReasoningAndModelId() {
    stubModel(new ClassificationResult(ModelTier.CLOUD_PREMIUM,
        "multi-step refactoring"));

    var justification = classifier.classify("Refactor").justification();

    var llm = assertInstanceOf(ClassificationJustification.Llm.class,
        justification);
    assertEquals(ClassificationStrategy.LLM, llm.strategy());
    assertEquals("multi-step refactoring", llm.reasoning());
    assertEquals("classifier-model", llm.classifierModelId());
  }

  @Test
  void modelIdFallsBackToTheRegistryEntryTierWhenUnset() {
    properties.setModelId("");
    ModelRegistryProperties registry = new ModelRegistryProperties();
    ModelRegistryProperties.ModelEntry entry =
        new ModelRegistryProperties.ModelEntry();
    entry.setModelId("registry-entry-model");
    entry.setTier(ModelTier.CLOUD_ENTRY);
    registry.getRegistry().put("entry", entry);
    classifier = new LlmComplexityClassifier(
        chatClient, properties, registry, heuristic);
    stubModel(new ClassificationResult(ModelTier.LOCAL, "trivial"));

    var llm = assertInstanceOf(ClassificationJustification.Llm.class,
        classifier.classify("hi").justification());

    assertEquals("registry-entry-model", llm.classifierModelId());
  }

  @Test
  void fallbackIsDistinguishableFromANominalDecision() {
    stubModel(new ClassificationResult(null, "unsure"));
    when(heuristic.classify("Refactor")).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));

    var justification = classifier.classify("Refactor").justification();

    var fallback = assertInstanceOf(ClassificationJustification.Fallback.class,
        justification);
    assertEquals(ClassificationStrategy.LLM, fallback.fallbackFrom());
    assertEquals(ClassificationJustification.FallbackCause.NO_TIER_RETURNED,
        fallback.cause());
    // The strategy that actually decided, not the one configured.
    assertEquals(ClassificationStrategy.HEURISTIC, fallback.strategy());
    assertInstanceOf(ClassificationJustification.Heuristic.class,
        fallback.effective());
  }

  @Test
  void llmErrorIsReportedAsItsOwnCause() {
    when(responseSpec.entity(ClassificationResult.class))
        .thenThrow(new RuntimeException("boom"));
    when(heuristic.classify("anything")).thenReturn(outcome(ModelTier.LOCAL));

    var fallback = assertInstanceOf(ClassificationJustification.Fallback.class,
        classifier.classify("anything").justification());

    assertEquals(ClassificationJustification.FallbackCause.LLM_ERROR,
        fallback.cause());
  }

  @Test
  void failSafePremiumIsNotPresentedAsAJudgement() {
    // Fallback disabled: premium is chosen defensively, and the trace says so
    // rather than claiming the heuristic decided.
    properties.setFallbackToHeuristic(false);
    when(responseSpec.entity(ClassificationResult.class))
        .thenThrow(new RuntimeException("boom"));

    var justification = classifier.classify("anything").justification();

    var failSafe = assertInstanceOf(ClassificationJustification.FailSafe.class,
        justification);
    assertEquals(ClassificationStrategy.LLM, failSafe.fallbackFrom());
    assertEquals(ClassificationJustification.FallbackCause.LLM_ERROR,
        failSafe.cause());
  }

  private void stubModel(ClassificationResult result) {
    when(responseSpec.entity(ClassificationResult.class)).thenReturn(result);
  }
}
