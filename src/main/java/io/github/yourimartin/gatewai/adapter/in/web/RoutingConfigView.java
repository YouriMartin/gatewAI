package io.github.yourimartin.gatewai.adapter.in.web;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** JSON view of the runtime routing configuration (Phase 5.2). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RoutingConfigView(
    String strategy,
    int entryLengthThreshold,
    int premiumLengthThreshold,
    List<String> premiumKeywords
) {

  public RoutingConfigView {
    premiumKeywords =
        premiumKeywords == null ? List.of() : List.copyOf(premiumKeywords);
  }

  static RoutingConfigView of(RoutingConfig config) {
    return new RoutingConfigView(
        config.strategy(), config.entryLengthThreshold(),
        config.premiumLengthThreshold(), config.premiumKeywords());
  }

  RoutingConfig toDomain() {
    return new RoutingConfig(
        strategy, entryLengthThreshold, premiumLengthThreshold, premiumKeywords);
  }
}
