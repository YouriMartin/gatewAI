package io.github.yourimartin.gatewai.adapter.in.web;

import java.util.List;
import java.util.Locale;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** JSON view of the runtime routing configuration (Phase 5.2). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RoutingConfigView(
    String strategy,
    int entryLengthThreshold,
    int premiumLengthThreshold,
    List<String> premiumKeywords,
    double routeSimilarityThreshold,
    List<RouteView> routes
) {

  public RoutingConfigView {
    premiumKeywords =
        premiumKeywords == null ? List.of() : List.copyOf(premiumKeywords);
    routes = routes == null ? List.of() : List.copyOf(routes);
  }

  static RoutingConfigView of(RoutingConfig config) {
    return new RoutingConfigView(
        config.strategy(), config.entryLengthThreshold(),
        config.premiumLengthThreshold(), config.premiumKeywords(),
        config.routeSimilarityThreshold(),
        config.routes().stream().map(RouteView::of).toList());
  }

  RoutingConfig toDomain() {
    return new RoutingConfig(
        strategy, entryLengthThreshold, premiumLengthThreshold, premiumKeywords,
        routeSimilarityThreshold,
        routes.stream().map(RouteView::toDomain).toList());
  }

  /** One semantic route as exposed over the admin API. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RouteView(String name, String tier, List<String> examples) {

    public RouteView {
      examples = examples == null ? List.of() : List.copyOf(examples);
    }

    static RouteView of(SemanticRoute route) {
      return new RouteView(route.name(),
          route.tier() == null
              ? null : route.tier().name().toLowerCase(Locale.ROOT),
          route.examples());
    }

    SemanticRoute toDomain() {
      if (tier == null || tier.isBlank()) {
        throw new IllegalArgumentException(
            "route '" + name + "' must have a tier");
      }
      return new SemanticRoute(name,
          ModelTier.valueOf(tier.toUpperCase(Locale.ROOT)), examples);
    }
  }
}
