package io.github.yourimartin.gatewai.application.service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.port.in.RoutingConfigUseCase;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;

import org.springframework.stereotype.Service;

/** Validates and applies routing config changes (Phase 5.2). */
@Service
class RoutingConfigService implements RoutingConfigUseCase {

  private static final Set<String> STRATEGIES =
      Set.of("heuristic", "embedding", "llm");

  private final RoutingConfigPort port;

  RoutingConfigService(RoutingConfigPort port) {
    this.port = port;
  }

  @Override
  public RoutingConfig current() {
    return port.get();
  }

  @Override
  public void update(RoutingConfig config) {
    validate(config);
    port.update(config);
  }

  private static void validate(RoutingConfig config) {
    String strategy = config.strategy() == null
        ? null : config.strategy().toLowerCase(Locale.ROOT);
    if (strategy == null || !STRATEGIES.contains(strategy)) {
      throw new IllegalArgumentException(
          "strategy must be 'heuristic', 'embedding' or 'llm'");
    }
    if (config.entryLengthThreshold() < 0 || config.premiumLengthThreshold() < 0) {
      throw new IllegalArgumentException("thresholds must be >= 0");
    }
    if (config.entryLengthThreshold() > config.premiumLengthThreshold()) {
      throw new IllegalArgumentException(
          "entry threshold must be <= premium threshold");
    }
    if (config.routeSimilarityThreshold() < 0
        || config.routeSimilarityThreshold() > 1) {
      throw new IllegalArgumentException(
          "route similarity threshold must be within [0, 1]");
    }
    validateRoutes(config, strategy);
  }

  private static void validateRoutes(RoutingConfig config, String strategy) {
    if ("embedding".equals(strategy) && config.routes().isEmpty()) {
      throw new IllegalArgumentException(
          "embedding strategy requires at least one route");
    }
    Set<String> names = new HashSet<>();
    for (SemanticRoute route : config.routes()) {
      if (route.name() == null || route.name().isBlank()) {
        throw new IllegalArgumentException("route name must not be blank");
      }
      if (!names.add(route.name())) {
        throw new IllegalArgumentException(
            "duplicate route name: " + route.name());
      }
      if (route.tier() == null) {
        throw new IllegalArgumentException(
            "route '" + route.name() + "' must have a tier");
      }
      if (route.examples().isEmpty()
          || route.examples().stream().allMatch(String::isBlank)) {
        throw new IllegalArgumentException(
            "route '" + route.name() + "' needs at least one example");
      }
    }
  }
}
