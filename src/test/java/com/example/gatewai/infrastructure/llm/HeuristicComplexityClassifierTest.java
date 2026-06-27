package com.example.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.gatewai.domain.model.ModelTier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeuristicComplexityClassifierTest {

  private HeuristicComplexityClassifier classifier;

  @BeforeEach
  void setUp() {
    classifier = new HeuristicComplexityClassifier();
  }

  // ---- LOCAL tier ----

  @Test
  void nullTextReturnsLocal() {
    assertEquals(ModelTier.LOCAL, classifier.classify(null));
  }

  @Test
  void blankTextReturnsLocal() {
    assertEquals(ModelTier.LOCAL, classifier.classify("   "));
  }

  @Test
  void shortSimpleTextReturnsLocal() {
    assertEquals(ModelTier.LOCAL, classifier.classify("Hello!"));
  }

  @Test
  void textAtEntryThresholdReturnsLocal() {
    String text = "x".repeat(
        HeuristicComplexityClassifier.ENTRY_LENGTH_THRESHOLD);
    assertEquals(ModelTier.LOCAL, classifier.classify(text));
  }

  // ---- CLOUD_ENTRY tier ----

  @Test
  void mediumLengthTextReturnsCloudEntry() {
    String text = "x".repeat(
        HeuristicComplexityClassifier.ENTRY_LENGTH_THRESHOLD + 1);
    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify(text));
  }

  @Test
  void textJustBelowPremiumThresholdReturnsCloudEntry() {
    String text = "x".repeat(
        HeuristicComplexityClassifier.PREMIUM_LENGTH_THRESHOLD);
    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify(text));
  }

  // ---- CLOUD_PREMIUM tier (length) ----

  @Test
  void longTextReturnsCloudPremium() {
    String text = "x".repeat(
        HeuristicComplexityClassifier.PREMIUM_LENGTH_THRESHOLD + 1);
    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify(text));
  }

  // ---- CLOUD_PREMIUM tier (code blocks) ----

  @Test
  void fencedCodeBlockReturnsCloudPremium() {
    String text = "Fix this:\n```java\nSystem.out.println();\n```";
    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify(text));
  }

  @Test
  void tildeCodeBlockReturnsCloudPremium() {
    String text = "Review:\n~~~\nsome code\n~~~";
    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify(text));
  }

  // ---- CLOUD_PREMIUM tier (keywords) ----

  @Test
  void refactorKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Refactor this class"));
  }

  @Test
  void architectureKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Propose an architecture"));
  }

  @Test
  void frenchKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Démontrer le concept"));
  }

  @Test
  void analyzeKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Analyze this data set"));
  }

  @Test
  void debugKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Debug this function"));
  }

  @Test
  void algorithmKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Explain this algorithm"));
  }

  @Test
  void securityKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Check for security issues"));
  }

  @Test
  void vulnerabilityFrenchKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Trouver la vulnérabilité"));
  }

  @Test
  void designPatternKeywordReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Use a design pattern"));
  }

  @Test
  void scalabilityPartialMatchReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Improve scalability"));
  }

  @Test
  void migrationPartialMatchReturnsCloudPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Plan the migration"));
  }

  // ---- Case insensitivity ----

  @Test
  void keywordMatchIsCaseInsensitive() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("REFACTOR everything"));
  }

  // ---- Priority: code block wins over short length ----

  @Test
  void codeBlockInShortTextStillReturnsPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Fix ```x```"));
  }

  // ---- Priority: keyword wins over short length ----

  @Test
  void keywordInShortTextStillReturnsPremium() {
    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("Refactor it"));
  }
}
