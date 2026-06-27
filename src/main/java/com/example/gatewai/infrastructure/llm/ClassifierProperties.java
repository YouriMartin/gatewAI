package com.example.gatewai.infrastructure.llm;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hot-configurable rules for request complexity classification.
 *
 * <p>Read on every {@code classify} call so that changes pushed at runtime
 * (e.g. via an Actuator refresh) take effect without rebuilding any bean.
 */
@ConfigurationProperties(prefix = "gatewai.classifier")
class ClassifierProperties {

  /** Which classification strategy the router uses. */
  private ClassifierStrategy strategy = ClassifierStrategy.HEURISTIC;

  /**
   * Model id used by the classifier client. When blank, the cheapest
   * cloud-entry model from the registry is used.
   */
  private String modelId = "";

  /** Low temperature keeps classification deterministic. */
  private double temperature;

  /** Instruction sent to the model describing the tiers and rules. */
  private String systemPrompt = """
      You are a request-complexity classifier for an LLM routing gateway.
      Classify the user request into exactly one tier based on the reasoning \
      effort it genuinely requires:
      - LOCAL: trivial requests (greetings, simple factual questions, short \
      rewrites) a small local model can handle.
      - CLOUD_ENTRY: moderately complex requests (summaries, basic code \
      questions, medium-length text) needing a cheap cloud model.
      - CLOUD_PREMIUM: complex requests (architecture, refactoring, debugging, \
      algorithms, security analysis, long or multi-step reasoning) needing a \
      premium model.
      Prefer the cheapest tier that can still answer correctly.""";

  /**
   * When the LLM call fails or returns no tier, fall back to the heuristic
   * classifier. When {@code false}, an unclassifiable request is routed to
   * {@code CLOUD_PREMIUM} (fail safe toward answer quality).
   */
  private boolean fallbackToHeuristic = true;

  /** Heuristic V1: text longer than this (chars) routes at least to entry. */
  private int entryLengthThreshold = 100;

  /** Heuristic V1: text longer than this (chars) routes to premium. */
  private int premiumLengthThreshold = 500;

  /** Heuristic V1: substrings that force a request to the premium tier. */
  private List<String> premiumKeywords = List.of(
      "refactor", "architecture", "demonstrate", "démontrer",
      "analyze", "analyser", "optimize", "optimiser",
      "debug", "algorithm", "algorithme",
      "security", "sécurité", "vulnerability", "vulnérabilité",
      "design pattern", "scalab", "migrat");

  ClassifierStrategy getStrategy() {
    return strategy;
  }

  void setStrategy(ClassifierStrategy strategy) {
    this.strategy = strategy;
  }

  String getModelId() {
    return modelId;
  }

  void setModelId(String modelId) {
    this.modelId = modelId;
  }

  double getTemperature() {
    return temperature;
  }

  void setTemperature(double temperature) {
    this.temperature = temperature;
  }

  String getSystemPrompt() {
    return systemPrompt;
  }

  void setSystemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
  }

  boolean isFallbackToHeuristic() {
    return fallbackToHeuristic;
  }

  void setFallbackToHeuristic(boolean fallbackToHeuristic) {
    this.fallbackToHeuristic = fallbackToHeuristic;
  }

  int getEntryLengthThreshold() {
    return entryLengthThreshold;
  }

  void setEntryLengthThreshold(int entryLengthThreshold) {
    this.entryLengthThreshold = entryLengthThreshold;
  }

  int getPremiumLengthThreshold() {
    return premiumLengthThreshold;
  }

  void setPremiumLengthThreshold(int premiumLengthThreshold) {
    this.premiumLengthThreshold = premiumLengthThreshold;
  }

  List<String> getPremiumKeywords() {
    return premiumKeywords;
  }

  void setPremiumKeywords(List<String> premiumKeywords) {
    this.premiumKeywords = premiumKeywords == null
        ? List.of() : List.copyOf(premiumKeywords);
  }
}
