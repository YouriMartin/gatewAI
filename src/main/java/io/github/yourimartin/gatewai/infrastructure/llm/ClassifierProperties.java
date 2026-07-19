package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ModelTier;

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
  private ClassifierStrategy strategy = ClassifierStrategy.EMBEDDING;

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

  /**
   * Embedding strategy: minimum cosine similarity (0..1) between the request
   * and a route's closest example. Below it, the heuristic decides instead.
   */
  private double routeSimilarityThreshold = 0.60;

  /**
   * Embedding strategy: the semantic routes. Default routes carry bilingual
   * EN/FR examples (runtime data, like the premium keywords) so both languages
   * classify well out of the box; add languages by adding examples.
   */
  private List<Route> routes = defaultRoutes();

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

  double getRouteSimilarityThreshold() {
    return routeSimilarityThreshold;
  }

  void setRouteSimilarityThreshold(double routeSimilarityThreshold) {
    this.routeSimilarityThreshold = routeSimilarityThreshold;
  }

  List<Route> getRoutes() {
    return routes;
  }

  void setRoutes(List<Route> routes) {
    this.routes = routes == null ? new ArrayList<>() : routes;
  }

  /** One semantic route: a named intent bucket, its tier and its examples. */
  static class Route {

    private String name;
    private ModelTier tier;
    private List<String> examples = new ArrayList<>();

    Route() {
    }

    Route(String name, ModelTier tier, List<String> examples) {
      this.name = name;
      this.tier = tier;
      this.examples = new ArrayList<>(examples);
    }

    String getName() {
      return name;
    }

    void setName(String name) {
      this.name = name;
    }

    ModelTier getTier() {
      return tier;
    }

    void setTier(ModelTier tier) {
      this.tier = tier;
    }

    List<String> getExamples() {
      return examples;
    }

    void setExamples(List<String> examples) {
      this.examples = examples == null ? new ArrayList<>() : examples;
    }
  }

  private static List<Route> defaultRoutes() {
    List<Route> defaults = new ArrayList<>();
    defaults.add(new Route("casual-chat", ModelTier.LOCAL, List.of(
        "Hello, how are you today?",
        "Bonjour, comment ça va ?",
        "What is the capital of Italy?",
        "Quelle heure est-il à Tokyo ?",
        "Translate 'good morning' into Spanish",
        "Merci beaucoup pour ton aide !",
        "Tell me a short joke")));
    defaults.add(new Route("drafting-and-summaries", ModelTier.CLOUD_ENTRY, List.of(
        "Summarize this article in three bullet points",
        "Résume ce texte en trois phrases",
        "Write a short product description for a coffee mug",
        "Rédige un e-mail poli pour reporter une réunion",
        "Explain what an API is in simple terms",
        "Corrige l'orthographe et le style de ce paragraphe",
        "Draft a LinkedIn post announcing our new feature")));
    defaults.add(new Route("code-and-analysis", ModelTier.CLOUD_PREMIUM, List.of(
        "Refactor this Java service to use dependency injection",
        "Analyse la complexité de cet algorithme et propose une optimisation",
        "Design a database schema for a multi-tenant SaaS application",
        "Debug this stack trace and explain the root cause",
        "Écris une fonction qui parse un fichier CSV et gère les erreurs",
        "Review this code for security vulnerabilities",
        "Compare event sourcing and CRUD for an audit-heavy domain",
        "Démontre pourquoi cet algorithme est en O(n log n)")));
    return defaults;
  }
}
