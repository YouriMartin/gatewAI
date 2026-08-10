package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.HeuristicRule;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.ModelTier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeuristicComplexityClassifierTest {

  private ClassifierProperties properties;
  private HeuristicComplexityClassifier classifier;

  @BeforeEach
  void setUp() {
    properties = new ClassifierProperties();
    classifier = new HeuristicComplexityClassifier(properties);
  }

  // ---- LOCAL tier ----

  @Test
  void nullTextReturnsLocal() {
    assertEquals(ModelTier.LOCAL, classifier.classify(null).tier());
  }

  @Test
  void blankTextReturnsLocal() {
    assertEquals(ModelTier.LOCAL, classifier.classify("   ").tier());
  }

  @Test
  void shortSimpleTextReturnsLocal() {
    assertEquals(ModelTier.LOCAL, classifier.classify("Hello!").tier());
  }

  @Test
  void textAtEntryThresholdReturnsLocal() {
    String text = "x".repeat(
        properties.getEntryLengthThreshold());
    assertEquals(ModelTier.LOCAL, classifier.classify(text).tier());
  }

  // ---- CLOUD_ENTRY tier ----

  @Test
  void mediumLengthTextReturnsCloudEntry() {
    String text = "x".repeat(
        properties.getEntryLengthThreshold() + 1);
    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify(text).tier());
  }

  @Test
  void textJustBelowPremiumThresholdReturnsCloudEntry() {
    String text = "x".repeat(
        properties.getPremiumLengthThreshold());
    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify(text).tier());
  }

  // ---- CLOUD_PREMIUM tier (length) ----

  @Test
  void longTextReturnsCloudPremium() {
    String text = "x".repeat(
        properties.getPremiumLengthThreshold() + 1);
    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify(text).tier());
  }

  // ---- CLOUD_PREMIUM tier (code blocks) ----

  @Test
  void fencedCodeBlockReturnsCloudPremium() {
    String text = "Fix this:\n```java\nSystem.out.println();\n```";
    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify(text).tier());
  }

  @Test
  void tildeCodeBlockReturnsCloudPremium() {
    String text = "Review:\n~~~\nsome code\n~~~";
    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify(text).tier());
  }

  // ---- CLOUD_PREMIUM tier (keywords) ----

  @Test
  void refactorKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Refactor this class").tier());
  }

  @Test
  void architectureKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Propose an architecture").tier());
  }

  @Test
  void frenchKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Démontrer le concept").tier());
  }

  @Test
  void analyzeKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Analyze this data set").tier());
  }

  @Test
  void debugKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Debug this function").tier());
  }

  @Test
  void algorithmKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Explain this algorithm").tier());
  }

  @Test
  void securityKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Check for security issues").tier());
  }

  @Test
  void vulnerabilityFrenchKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Trouver la vulnérabilité").tier());
  }

  @Test
  void designPatternKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Use a design pattern").tier());
  }

  @Test
  void scalabilityPartialMatchReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Improve scalability").tier());
  }

  @Test
  void migrationPartialMatchReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Plan the migration").tier());
  }

  // ---- Case insensitivity ----

  @Test
  void keywordMatchIsCaseInsensitive() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("REFACTOR everything").tier());
  }

  // ---- Priority: code block wins over short length ----

  @Test
  void codeBlockInShortTextStillReturnsPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Fix ```x```").tier());
  }

  // ---- Priority: keyword wins over short length ----

  @Test
  void keywordInShortTextStillReturnsPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Refactor it").tier());
  }

  // ---- Justification (v2 batch 1) ----

  @Test
  void keywordJustificationNamesTheMatchedKeyword() {
    var justification = classifier.classify("REFACTOR everything").justification();

    var heuristic = assertInstanceOf(
        ClassificationJustification.Heuristic.class, justification);
    assertEquals(ClassificationStrategy.HEURISTIC, heuristic.strategy());
    assertEquals(HeuristicRule.PREMIUM_KEYWORD, heuristic.rule());
    assertEquals("refactor", heuristic.matchedKeyword());
  }

  @Test
  void lengthJustificationCarriesWhatWasObservedAndAgainstWhat() {
    String text = "x".repeat(properties.getPremiumLengthThreshold() + 1);

    var heuristic = assertInstanceOf(
        ClassificationJustification.Heuristic.class,
        classifier.classify(text).justification());

    assertEquals(HeuristicRule.PREMIUM_LENGTH, heuristic.rule());
    assertEquals(text.length(), heuristic.observedLength());
    assertEquals(properties.getPremiumLengthThreshold(), heuristic.threshold());
  }

  @Test
  void entryLengthJustificationNamesTheEntryThreshold() {
    String text = "x".repeat(properties.getEntryLengthThreshold() + 1);

    var heuristic = assertInstanceOf(
        ClassificationJustification.Heuristic.class,
        classifier.classify(text).justification());

    assertEquals(HeuristicRule.ENTRY_LENGTH, heuristic.rule());
    assertEquals(properties.getEntryLengthThreshold(), heuristic.threshold());
  }

  @Test
  void codeFenceJustificationNeedsNoObservedValue() {
    var heuristic = assertInstanceOf(
        ClassificationJustification.Heuristic.class,
        classifier.classify("Fix ```x```").justification());

    assertEquals(HeuristicRule.CODE_FENCE, heuristic.rule());
    assertNull(heuristic.matchedKeyword());
    assertNull(heuristic.observedLength());
  }

  @Test
  void everyRuleProducesAJustification() {
    assertEquals(HeuristicRule.BLANK_TEXT, rule(classifier.classify(null)));
    assertEquals(HeuristicRule.DEFAULT, rule(classifier.classify("Hello!")));
    assertEquals(HeuristicRule.CODE_FENCE, rule(classifier.classify("a ``` b")));
    assertEquals(HeuristicRule.PREMIUM_KEYWORD,
        rule(classifier.classify("debug it")));
  }

  private static HeuristicRule rule(ClassificationOutcome outcome) {
    return assertInstanceOf(ClassificationJustification.Heuristic.class,
        outcome.justification()).rule();
  }
}
