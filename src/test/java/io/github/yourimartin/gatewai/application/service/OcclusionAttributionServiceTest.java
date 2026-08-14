package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.yourimartin.gatewai.domain.model.AttributionKey;
import io.github.yourimartin.gatewai.domain.model.AttributionReport;
import io.github.yourimartin.gatewai.domain.model.AttributionStatus;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SegmentAttribution;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.port.out.AttributionCache;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The attribution service against a <b>bag-of-words</b> embedder: a vector whose
 * dimensions are keywords, so cosine similarity is exactly computable by hand.
 * A real model would make the expected values unknowable and the test would
 * assert whatever the model happened to say.
 */
class OcclusionAttributionServiceTest {

  /** The vocabulary the fake embedder projects onto. */
  private static final List<String> VOCABULARY =
      List.of("refactor", "architecture", "hello", "thanks", "summarize");

  private CountingEmbedder embedder;
  private StubRoutingConfig routingConfig;
  private SemanticRouteIndex index;
  private MapCache cache;
  private OcclusionAttributionService service;

  @BeforeEach
  void setUp() {
    embedder = new CountingEmbedder();
    routingConfig = new StubRoutingConfig(config("embedding"));
    index = new SemanticRouteIndex(embedder);
    cache = new MapCache();
    service = new OcclusionAttributionService(embedder, routingConfig, index,
        cache, 20, 200);
  }

  @Test
  @DisplayName("the segment carrying the matched route ranks first")
  void attributesTheDecisionToTheSegmentThatCausedIt() {
    AttributionReport report = service.attribute(
        "Hello there. Refactor the architecture of this service.");

    assertEquals(AttributionStatus.COMPUTED, report.status());
    assertEquals("code-and-analysis", report.route());
    assertEquals(ModelTier.CLOUD_PREMIUM, report.tier());

    SegmentAttribution strongest = report.segments().getFirst();
    assertTrue(strongest.segment().toLowerCase(Locale.ROOT).contains("refactor"),
        "the greeting cannot be what sent this to premium: " + strongest);
    assertTrue(strongest.contribution() > 0);
    assertEquals(1, strongest.rank());
  }

  @Test
  @DisplayName("shares are readable: they sum to one over what contributed")
  void sharesAreNormalized() {
    AttributionReport report = service.attribute(
        "Hello there. Refactor the architecture of this service.");

    double total = report.segments().stream()
        .mapToDouble(SegmentAttribution::share).sum();
    assertEquals(1.0, total, 1e-9);
  }

  @Test
  @DisplayName("it explains a named quantity: route, utterance and similarity")
  void reportNamesWhatItDecomposes() {
    AttributionReport report = service.attribute("Refactor this service.");

    assertEquals("Refactor the architecture of this Java service",
        report.matchedUtterance());
    assertTrue(report.similarity() > 0);
    assertEquals("fake-embedder", report.embeddingModel());
    assertFalse(report.routingConfigVersion().isBlank());
  }

  @Test
  @DisplayName("one embedding per segment, plus one for the prompt")
  void costIsBoundedBySegmentCount() {
    // Warm the route index first: indexing the examples is a one-off, and what
    // this pins down is the per-attribution cost.
    service.attribute("Refactor this service.");
    embedder.reset();

    service.attribute("Hello there. Refactor the architecture. Thanks!");

    assertEquals(4, embedder.promptEmbeddings(),
        "1 prompt + 3 occluded variants; route examples are indexed once");
  }

  @Test
  @DisplayName("the cap bounds the cost whatever the prompt looks like")
  void segmentCapIsRespected() {
    OcclusionAttributionService capped = new OcclusionAttributionService(
        embedder, routingConfig, index, cache, 3, 200);

    AttributionReport report = capped.attribute(
        "Refactor it. Add tests. Ship it. Then benchmark it. Finally document it.");

    assertEquals(3, report.segments().size());
  }

  @Test
  @DisplayName("a second call for the same prompt embeds nothing")
  void reportsAreCached() {
    service.attribute("Refactor this service.");
    embedder.reset();

    AttributionReport again = service.attribute("Refactor this service.");

    assertEquals(0, embedder.promptEmbeddings());
    assertEquals(AttributionStatus.COMPUTED, again.status());
  }

  @Test
  @DisplayName("editing a route invalidates what the cache would keep explaining")
  void routingConfigChangeInvalidatesTheCache() {
    service.attribute("Refactor this service.");
    routingConfig.set(config("embedding",
        "Refactor the architecture of this Java service (edited)"));
    embedder.reset();

    service.attribute("Refactor this service.");

    assertTrue(embedder.promptEmbeddings() > 0,
        "the report explained a route example that no longer exists");
  }

  @Test
  @DisplayName("nothing to attribute when the strategy is not about similarity")
  void heuristicStrategyIsNotApplicable() {
    routingConfig.set(config("heuristic"));

    AttributionReport report = service.attribute("Refactor this service.");

    assertEquals(AttributionStatus.NOT_APPLICABLE_STRATEGY, report.status());
    assertTrue(report.segments().isEmpty());
    assertEquals(0, embedder.promptEmbeddings(),
        "an inapplicable question must not cost a single embedding call");
  }

  @Test
  @DisplayName("the cascade is attributable: its level 2 is the same similarity")
  void cascadeStrategyIsApplicable() {
    routingConfig.set(config("cascade"));

    assertEquals(AttributionStatus.COMPUTED,
        service.attribute("Refactor this service.").status());
  }

  @Test
  @DisplayName("no routes, nothing to be closest to")
  void noRoutesConfigured() {
    routingConfig.set(new RoutingConfig("embedding", 100, 500, List.of(), 0.6,
        List.of()));

    assertEquals(AttributionStatus.NO_ROUTES_CONFIGURED,
        service.attribute("Refactor this service.").status());
  }

  @Test
  @DisplayName("an empty prompt is answered, not embedded")
  void blankPrompt() {
    assertEquals(AttributionStatus.EMPTY_PROMPT,
        service.attribute("   ").status());
    assertEquals(0, embedder.promptEmbeddings());
  }

  @Test
  @DisplayName("a failing embedder surfaces: an admin asking why is owed the error")
  void embeddingFailurePropagates() {
    embedder.failOn("Refactor this service.");

    assertThrows(RuntimeException.class, () ->
        service.attribute("Refactor this service. And add tests."));
  }

  private static RoutingConfig config(String strategy) {
    return config(strategy, "Refactor the architecture of this Java service");
  }

  private static RoutingConfig config(String strategy, String premiumExample) {
    return new RoutingConfig(strategy, 100, 500, List.of("refactor"), 0.6,
        List.of(
            new SemanticRoute("casual-chat", ModelTier.LOCAL,
                List.of("Hello, how are you?", "Thanks a lot")),
            new SemanticRoute("drafting", ModelTier.CLOUD_ENTRY,
                List.of("Summarize this article")),
            new SemanticRoute("code-and-analysis", ModelTier.CLOUD_PREMIUM,
                List.of(premiumExample, "Review the architecture"))));
  }

  /**
   * Bag-of-words embedder: dimension <i>i</i> counts occurrences of vocabulary
   * word <i>i</i>. Similarity is then a cosine over word counts — crude, and
   * exactly predictable, which is the point.
   */
  private static final class CountingEmbedder implements TextEmbedder {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile String failing;

    @Override
    public float[] embed(String text) {
      if (text.equals(failing)) {
        throw new IllegalStateException("embedding model unreachable");
      }
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

    void failOn(String text) {
      this.failing = text;
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
  }

  private static final class MapCache implements AttributionCache {

    private final ConcurrentHashMap<AttributionKey, AttributionReport> reports =
        new ConcurrentHashMap<>();

    @Override
    public Optional<AttributionReport> get(AttributionKey key) {
      return Optional.ofNullable(reports.get(key));
    }

    @Override
    public void put(AttributionKey key, AttributionReport report) {
      reports.put(key, report);
    }
  }
}
