package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;

import org.junit.jupiter.api.Test;

class RoutingConfigJsonTest {

  @Test
  void keywordsRoundTripInOrder() {
    List<String> keywords = List.of("refactor", "sécurité", "design pattern");

    assertEquals(keywords, RoutingConfigJson.keywordsFromJson(
        RoutingConfigJson.keywordsToJson(keywords)));
  }

  @Test
  void routesRoundTripWithTiersAndExampleOrder() {
    List<SemanticRoute> routes = List.of(
        new SemanticRoute("casual-chat", ModelTier.LOCAL,
            List.of("Bonjour, comment ça va ?", "Tell me a short joke")),
        new SemanticRoute("code", ModelTier.CLOUD_PREMIUM,
            List.of("Refactor this Java service")));

    List<SemanticRoute> parsed = RoutingConfigJson.routesFromJson(
        RoutingConfigJson.routesToJson(routes));

    // Order is significant: it decides ties, and RoutingConfigVersion hashes it.
    assertEquals(routes, parsed);
  }

  @Test
  void aRouteWithoutATierSurvivesTheRoundTrip() {
    // An operator can save an incomplete route; usableRoutes() filters it out at
    // classification time. Dropping it here would be an edit nobody asked for.
    List<SemanticRoute> routes =
        List.of(new SemanticRoute("draft", null, List.of("todo")));

    List<SemanticRoute> parsed = RoutingConfigJson.routesFromJson(
        RoutingConfigJson.routesToJson(routes));

    assertEquals(1, parsed.size());
    assertNull(parsed.getFirst().tier());
    assertEquals("draft", parsed.getFirst().name());
  }

  @Test
  void emptyListsRoundTripAsEmptyRatherThanNull() {
    assertTrue(RoutingConfigJson.keywordsFromJson(
        RoutingConfigJson.keywordsToJson(List.of())).isEmpty());
    assertTrue(RoutingConfigJson.routesFromJson(
        RoutingConfigJson.routesToJson(List.of())).isEmpty());
  }

  @Test
  void aBlankColumnReadsAsEmpty() {
    assertTrue(RoutingConfigJson.keywordsFromJson(null).isEmpty());
    assertTrue(RoutingConfigJson.routesFromJson("").isEmpty());
  }
}
