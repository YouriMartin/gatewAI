package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps the two list-shaped parts of the routing config to and from their JSONB
 * columns (v3 lot B.1).
 *
 * <p>Hand-written for the same reasons as {@link JustificationJson}: the domain
 * records stay framework-free, and the shape of what is stored is readable in
 * one place rather than implied by annotations.
 *
 * <p>A route with no tier is stored as such. {@code RoutingConfig.usableRoutes()}
 * already filters those out at classification time, so an operator's incomplete
 * route survives a round trip instead of being dropped on save — which would be
 * a config edit nobody asked for.
 */
final class RoutingConfigJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RoutingConfigJson() {
  }

  static String keywordsToJson(List<String> keywords) {
    ArrayNode array = MAPPER.createArrayNode();
    for (String keyword : keywords) {
      array.add(keyword);
    }
    return MAPPER.writeValueAsString(array);
  }

  static List<String> keywordsFromJson(String json) {
    List<String> keywords = new ArrayList<>();
    JsonNode array = readArray(json);
    for (JsonNode keyword : array) {
      keywords.add(keyword.asString());
    }
    return keywords;
  }

  static String routesToJson(List<SemanticRoute> routes) {
    ArrayNode array = MAPPER.createArrayNode();
    for (SemanticRoute route : routes) {
      ObjectNode node = array.addObject();
      node.put("name", route.name());
      node.put("tier", route.tier() == null ? null : route.tier().name());
      ArrayNode examples = node.putArray("examples");
      for (String example : route.examples()) {
        examples.add(example);
      }
    }
    return MAPPER.writeValueAsString(array);
  }

  static List<SemanticRoute> routesFromJson(String json) {
    List<SemanticRoute> routes = new ArrayList<>();
    for (JsonNode node : readArray(json)) {
      List<String> examples = new ArrayList<>();
      for (JsonNode example : node.path("examples")) {
        examples.add(example.asString());
      }
      String tier = text(node, "tier");
      routes.add(new SemanticRoute(
          text(node, "name"),
          tier == null ? null : ModelTier.valueOf(tier),
          examples));
    }
    return routes;
  }

  private static JsonNode readArray(String json) {
    if (json == null || json.isBlank()) {
      return MAPPER.createArrayNode();
    }
    JsonNode node = MAPPER.readTree(json);
    return node.isArray() ? node : MAPPER.createArrayNode();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }
}
