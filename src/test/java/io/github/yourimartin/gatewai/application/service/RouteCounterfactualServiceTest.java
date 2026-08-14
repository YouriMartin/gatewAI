package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.yourimartin.gatewai.domain.model.Counterfactual;
import io.github.yourimartin.gatewai.domain.model.CounterfactualReport;
import io.github.yourimartin.gatewai.domain.model.CounterfactualStatus;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The counterfactual service against a <b>bag-of-words</b> embedder, for the
 * same reason batch 7's test uses one: every similarity is then a cosine over
 * word counts, so the expected ranking is a fact rather than whatever a model
 * happened to output that day.
 */
class RouteCounterfactualServiceTest {

  /** The vocabulary the fake embedder projects onto. */
  private static final List<String> VOCABULARY =
      List.of("refactor", "architecture", "hello", "thanks", "summarize",
          "article");

  private CountingEmbedder embedder;
  private StubRoutingConfig routingConfig;
  private RouteCounterfactualService service;

  @BeforeEach
  void setUp() {
    embedder = new CountingEmbedder();
    routingConfig = new StubRoutingConfig(config("embedding"));
    service = new RouteCounterfactualService(
        embedder, routingConfig, new SemanticRouteIndex(embedder), 3);
  }

  @Test
  @DisplayName("it names the outcome the request missed, and by how much")
  void reportsTheNearestAlternativeOutcome() {
    CounterfactualReport report = service.explore("Hello, thanks!");

    assertEquals(CounterfactualStatus.COMPUTED, report.status());
    assertEquals(ModelTier.LOCAL, report.chosenTier());

    Counterfactual nearest = report.alternatives().getFirst();
    assertFalse(nearest.tier() == report.chosenTier(),
        "an alternative that changes nothing is not a counterfactual");
    assertEquals(1, nearest.rank());
    assertEquals(report.chosenSimilarity() - nearest.similarity(),
        nearest.gap(), 1e-9);
  }

  @Test
  @DisplayName("one alternative per tier, closest first, capped")
  void alternativesAreOnePerTierAndCapped() {
    CounterfactualReport report = service.explore("Refactor the architecture.");

    List<ModelTier> tiers = report.alternatives().stream()
        .map(Counterfactual::tier).toList();

    assertEquals(tiers.stream().distinct().toList(), tiers,
        "the same tier twice crowds out the ones not yet shown");
    assertFalse(tiers.contains(report.chosenTier()));
    assertTrue(report.alternatives().size() <= 3);
  }

  @Test
  @DisplayName("gaps grow with the rank: the list really is closest first")
  void gapsAreOrdered() {
    CounterfactualReport report = service.explore("Summarize this article.");

    double previous = -1;
    for (Counterfactual alternative : report.alternatives()) {
      assertTrue(alternative.gap() >= previous, "not ordered by gap: " + report);
      previous = alternative.gap();
    }
  }

  @Test
  @DisplayName("the examples come from configuration, never from the request")
  void returnedUtterancesAreConfiguredExamples() {
    String prompt = "Refactor the architecture, and my password is hunter2.";

    CounterfactualReport report = service.explore(prompt);

    List<String> configured = routingConfig.get().routes().stream()
        .flatMap(route -> route.examples().stream()).toList();

    assertTrue(configured.contains(report.chosenUtterance()),
        "the matched utterance must be a configured example");
    for (Counterfactual alternative : report.alternatives()) {
      assertTrue(configured.contains(alternative.nearestUtterance()),
          "not a configured example: " + alternative.nearestUtterance());
      assertFalse(prompt.contains(alternative.nearestUtterance()),
          "an explanation must never quote the request back as an example");
    }
  }

  @Test
  @DisplayName("one embedding call per question: the ranking is the whole cost")
  void costsOneEmbeddingCall() {
    // Warm the route index first: indexing the examples is a one-off, and what
    // this pins down is the per-question cost.
    service.explore("Refactor this service.");
    embedder.reset();

    service.explore("Summarize this article.");

    assertEquals(1, embedder.promptEmbeddings());
  }

  @Test
  @DisplayName("nothing is cached: an edited route changes the next answer")
  void routeEditsApplyImmediately() {
    assertEquals("code-and-analysis",
        service.explore("Refactor the architecture.").chosenRoute());

    routingConfig.set(new RoutingConfig("embedding", 100, 500, List.of(), 0.6,
        List.of(
            new SemanticRoute("casual-chat", ModelTier.LOCAL,
                List.of("Hello, how are you?")),
            new SemanticRoute("renamed-code", ModelTier.CLOUD_PREMIUM,
                List.of("Refactor the architecture of this Java service")))));

    assertEquals("renamed-code",
        service.explore("Refactor the architecture.").chosenRoute());
  }

  @Test
  @DisplayName("the cascade ranks routes too, so it has counterfactuals")
  void cascadeStrategyIsApplicable() {
    routingConfig.set(config("cascade"));

    assertEquals(CounterfactualStatus.COMPUTED,
        service.explore("Refactor the architecture.").status());
  }

  @Test
  @DisplayName("a strategy that ranks nothing has nothing that came second")
  void heuristicStrategyIsNotApplicable() {
    routingConfig.set(config("heuristic"));

    CounterfactualReport report = service.explore("Refactor the architecture.");

    assertEquals(CounterfactualStatus.NOT_APPLICABLE_STRATEGY, report.status());
    assertTrue(report.alternatives().isEmpty());
    assertEquals(0, embedder.promptEmbeddings(),
        "an inapplicable question must not cost a single embedding call");
  }

  @Test
  @DisplayName("routes that all lead to one tier: no wording would have helped")
  void singleTierIsReportedAsSuch() {
    routingConfig.set(new RoutingConfig("embedding", 100, 500, List.of(), 0.6,
        List.of(
            new SemanticRoute("casual-chat", ModelTier.LOCAL,
                List.of("Hello, how are you?")),
            new SemanticRoute("small-talk", ModelTier.LOCAL,
                List.of("Thanks a lot")))));

    CounterfactualReport report = service.explore("Hello!");

    assertEquals(CounterfactualStatus.NO_ALTERNATIVE_TIER, report.status());
    assertTrue(report.alternatives().isEmpty());
    assertNotNull(report.chosenRoute(),
        "the chosen route is still worth reporting");
  }

  @Test
  @DisplayName("no routes, nothing to have come second")
  void noRoutesConfigured() {
    routingConfig.set(new RoutingConfig("embedding", 100, 500, List.of(), 0.6,
        List.of()));

    assertEquals(CounterfactualStatus.NO_ROUTES_CONFIGURED,
        service.explore("Refactor this service.").status());
  }

  @Test
  @DisplayName("an empty prompt is answered, not embedded")
  void blankPrompt() {
    assertEquals(CounterfactualStatus.EMPTY_PROMPT,
        service.explore("   ").status());
    assertEquals(0, embedder.promptEmbeddings());
  }

  @Test
  @DisplayName("provenance is stamped: these numbers are only valid for those rules")
  void provenanceIsReported() {
    CounterfactualReport report = service.explore("Refactor the architecture.");

    assertEquals("fake-embedder", report.embeddingModel());
    assertFalse(report.routingConfigVersion().isBlank());
  }

  private static RoutingConfig config(String strategy) {
    return new RoutingConfig(strategy, 100, 500, List.of("refactor"), 0.6,
        List.of(
            new SemanticRoute("casual-chat", ModelTier.LOCAL,
                List.of("Hello, how are you?", "Thanks a lot")),
            new SemanticRoute("drafting", ModelTier.CLOUD_ENTRY,
                List.of("Summarize this article")),
            new SemanticRoute("code-and-analysis", ModelTier.CLOUD_PREMIUM,
                List.of("Refactor the architecture of this Java service",
                    "Review the architecture"))));
  }

  /**
   * Bag-of-words embedder: dimension <i>i</i> counts occurrences of vocabulary
   * word <i>i</i>. Crude, and exactly predictable, which is the point.
   */
  private static final class CountingEmbedder implements TextEmbedder {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public float[] embed(String text) {
      calls.incrementAndGet();
      String lower = text.toLowerCase(Locale.ROOT);
      float[] vector = new float[VOCABULARY.size()];
      for (int i = 0; i < VOCABULARY.size(); i++) {
        int count = 0;
        int at = lower.indexOf(VOCABULARY.get(i));
        while (at >= 0) {
          count++;
          at = lower.indexOf(VOCABULARY.get(i), at + 1);
        }
        vector[i] = count;
      }
      return vector;
    }

    @Override
    public String modelId() {
      return "fake-embedder";
    }

    /** Route examples are indexed once and are not the cost under test. */
    void reset() {
      calls.set(0);
    }

    int promptEmbeddings() {
      return calls.get();
    }
  }

  private static final class StubRoutingConfig implements RoutingConfigPort {

    private volatile RoutingConfig config;

    StubRoutingConfig(RoutingConfig config) {
      this.config = config;
    }

    void set(RoutingConfig config) {
      this.config = config;
    }

    @Override
    public RoutingConfig get() {
      return config;
    }

    @Override
    public void update(RoutingConfig config) {
      this.config = config;
    }

    @Override
    public double cascadeMarginBand() {
      return 0.02;
    }

    @Override
    public void updateCascadeMarginBand(double band) {
      throw new UnsupportedOperationException("not part of this test");
    }
  }
}
