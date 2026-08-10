package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class RoutingConfigVersionTest {

  private static RoutingConfig config() {
    return new RoutingConfig("embedding", 100, 500,
        List.of("refactor", "debug"), 0.60,
        List.of(new SemanticRoute("chat", ModelTier.LOCAL,
            List.of("hello", "bonjour"))));
  }

  @Test
  void sameConfigProducesSameVersion() {
    assertEquals(RoutingConfigVersion.of(config()),
        RoutingConfigVersion.of(config()));
  }

  @Test
  void versionIsShortAndHex() {
    String version = RoutingConfigVersion.of(config());

    assertEquals(16, version.length());
    assertEquals(version.toLowerCase(java.util.Locale.ROOT), version);
  }

  @Test
  void everyFieldThatCanChangeAnOutcomeChangesTheVersion() {
    String base = RoutingConfigVersion.of(config());
    RoutingConfig source = config();

    assertNotEquals(base, RoutingConfigVersion.of(new RoutingConfig(
        "heuristic", 100, 500, source.premiumKeywords(), 0.60,
        source.routes())));
    assertNotEquals(base, RoutingConfigVersion.of(new RoutingConfig(
        "embedding", 101, 500, source.premiumKeywords(), 0.60,
        source.routes())));
    assertNotEquals(base, RoutingConfigVersion.of(new RoutingConfig(
        "embedding", 100, 501, source.premiumKeywords(), 0.60,
        source.routes())));
    assertNotEquals(base, RoutingConfigVersion.of(new RoutingConfig(
        "embedding", 100, 500, List.of("refactor"), 0.60, source.routes())));
    assertNotEquals(base, RoutingConfigVersion.of(new RoutingConfig(
        "embedding", 100, 500, source.premiumKeywords(), 0.61,
        source.routes())));
  }

  @Test
  void editingARouteExampleChangesTheVersion() {
    String base = RoutingConfigVersion.of(config());
    RoutingConfig source = config();

    String edited = RoutingConfigVersion.of(new RoutingConfig(
        "embedding", 100, 500, source.premiumKeywords(), 0.60,
        List.of(new SemanticRoute("chat", ModelTier.LOCAL,
            List.of("hello", "salut")))));

    assertNotEquals(base, edited);
  }

  @Test
  void retargetingARouteChangesTheVersion() {
    String base = RoutingConfigVersion.of(config());
    RoutingConfig source = config();

    String retargeted = RoutingConfigVersion.of(new RoutingConfig(
        "embedding", 100, 500, source.premiumKeywords(), 0.60,
        List.of(new SemanticRoute("chat", ModelTier.CLOUD_PREMIUM,
            List.of("hello", "bonjour")))));

    assertNotEquals(base, retargeted);
  }

  @Test
  void reorderingRoutesChangesTheVersion() {
    // Order decides ties, so it is part of the behaviour, not noise.
    RoutingConfig ordered = new RoutingConfig("embedding", 100, 500,
        List.of(), 0.60, List.of(
            new SemanticRoute("a", ModelTier.LOCAL, List.of("x")),
            new SemanticRoute("b", ModelTier.CLOUD_ENTRY, List.of("y"))));
    RoutingConfig reordered = new RoutingConfig("embedding", 100, 500,
        List.of(), 0.60, List.of(
            new SemanticRoute("b", ModelTier.CLOUD_ENTRY, List.of("y")),
            new SemanticRoute("a", ModelTier.LOCAL, List.of("x"))));

    assertNotEquals(RoutingConfigVersion.of(ordered),
        RoutingConfigVersion.of(reordered));
  }

  @Test
  void concatenatedExamplesCannotBeConfusedWithASplit() {
    // "ab" + "c" must not hash like "a" + "bc".
    RoutingConfig left = new RoutingConfig("embedding", 100, 500, List.of(),
        0.60, List.of(new SemanticRoute("r", ModelTier.LOCAL,
            List.of("ab", "c"))));
    RoutingConfig right = new RoutingConfig("embedding", 100, 500, List.of(),
        0.60, List.of(new SemanticRoute("r", ModelTier.LOCAL,
            List.of("a", "bc"))));

    assertNotEquals(RoutingConfigVersion.of(left),
        RoutingConfigVersion.of(right));
  }

  @Test
  void nullConfigHasNoVersion() {
    assertNull(RoutingConfigVersion.of(null));
  }
}
