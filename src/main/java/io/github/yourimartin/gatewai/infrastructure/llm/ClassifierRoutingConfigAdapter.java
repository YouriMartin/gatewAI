package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.ArrayList;
import java.util.Locale;
import java.util.stream.Collectors;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;

import org.springframework.stereotype.Component;

/**
 * Exposes the live {@link ClassifierProperties} as a {@link RoutingConfigPort}.
 * Updates mutate the singleton bean in place; the classifier reads it per call,
 * so changes take effect immediately (hot tuning, Phase 5.2).
 */
@Component
class ClassifierRoutingConfigAdapter implements RoutingConfigPort {

  private final ClassifierProperties properties;

  ClassifierRoutingConfigAdapter(ClassifierProperties properties) {
    this.properties = properties;
  }

  @Override
  public RoutingConfig get() {
    return new RoutingConfig(
        properties.getStrategy().name().toLowerCase(Locale.ROOT),
        properties.getEntryLengthThreshold(),
        properties.getPremiumLengthThreshold(),
        properties.getPremiumKeywords(),
        properties.getRouteSimilarityThreshold(),
        properties.getRoutes().stream()
            .map(route -> new SemanticRoute(
                route.getName(), route.getTier(), route.getExamples()))
            .toList());
  }

  @Override
  public void update(RoutingConfig config) {
    properties.setStrategy(ClassifierStrategy.valueOf(
        config.strategy().toUpperCase(Locale.ROOT)));
    properties.setEntryLengthThreshold(config.entryLengthThreshold());
    properties.setPremiumLengthThreshold(config.premiumLengthThreshold());
    properties.setPremiumKeywords(config.premiumKeywords());
    properties.setRouteSimilarityThreshold(config.routeSimilarityThreshold());
    properties.setRoutes(config.routes().stream()
        .map(route -> new ClassifierProperties.Route(
            route.name(), route.tier(), route.examples()))
        .collect(Collectors.toCollection(ArrayList::new)));
  }
}
