package io.github.yourimartin.gatewai.application.service;

import java.util.Locale;
import java.util.Set;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.port.in.RoutingConfigUseCase;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;

import org.springframework.stereotype.Service;

/** Validates and applies routing config changes (Phase 5.2). */
@Service
class RoutingConfigService implements RoutingConfigUseCase {

  private static final Set<String> STRATEGIES = Set.of("heuristic", "llm");

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
          "strategy must be 'heuristic' or 'llm'");
    }
    if (config.entryLengthThreshold() < 0 || config.premiumLengthThreshold() < 0) {
      throw new IllegalArgumentException("thresholds must be >= 0");
    }
    if (config.entryLengthThreshold() > config.premiumLengthThreshold()) {
      throw new IllegalArgumentException(
          "entry threshold must be <= premium threshold");
    }
  }
}
