package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClassifierRoutingConfigAdapterTest {

  private ClassifierProperties properties;
  private ClassifierRoutingConfigAdapter adapter;

  @BeforeEach
  void setUp() {
    properties = new ClassifierProperties();
    adapter = new ClassifierRoutingConfigAdapter(properties);
  }

  @Test
  void getReflectsCurrentProperties() {
    RoutingConfig config = adapter.get();

    assertEquals("embedding", config.strategy());
    assertEquals(100, config.entryLengthThreshold());
    assertEquals(500, config.premiumLengthThreshold());
    assertTrue(config.premiumKeywords().contains("refactor"));
    assertEquals(0.60, config.routeSimilarityThreshold());
    assertFalse(config.routes().isEmpty());
  }

  @Test
  void defaultRoutesCoverAllTiers() {
    List<SemanticRoute> routes = adapter.get().routes();

    for (ModelTier tier : ModelTier.values()) {
      assertTrue(routes.stream().anyMatch(route -> route.tier() == tier),
          "missing default route for tier " + tier);
    }
  }

  @Test
  void updateMutatesPropertiesLive() {
    List<SemanticRoute> routes = List.of(
        new SemanticRoute("code", ModelTier.CLOUD_PREMIUM,
            List.of("refactor this", "debug that")));

    adapter.update(new RoutingConfig("llm", 50, 800, List.of("foo", "bar"),
        0.75, routes));

    assertEquals(ClassifierStrategy.LLM, properties.getStrategy());
    assertEquals(50, properties.getEntryLengthThreshold());
    assertEquals(800, properties.getPremiumLengthThreshold());
    assertEquals(List.of("foo", "bar"), properties.getPremiumKeywords());
    assertEquals(0.75, properties.getRouteSimilarityThreshold());
    assertEquals(1, properties.getRoutes().size());
    assertEquals("code", properties.getRoutes().getFirst().getName());
    assertEquals(ModelTier.CLOUD_PREMIUM,
        properties.getRoutes().getFirst().getTier());
    assertEquals(List.of("refactor this", "debug that"),
        properties.getRoutes().getFirst().getExamples());
  }

  @Test
  void updateThenGetRoundTrips() {
    List<SemanticRoute> routes = List.of(
        new SemanticRoute("chat", ModelTier.LOCAL, List.of("hello")));
    RoutingConfig config = new RoutingConfig("embedding", 10, 20,
        List.of("kw"), 0.5, routes);

    adapter.update(config);

    assertEquals(config, adapter.get());
  }
}
