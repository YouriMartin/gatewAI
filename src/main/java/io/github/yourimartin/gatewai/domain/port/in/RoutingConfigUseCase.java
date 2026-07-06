package io.github.yourimartin.gatewai.domain.port.in;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;

/** Reads and updates the routing configuration at runtime (Phase 5.2). */
public interface RoutingConfigUseCase {

  RoutingConfig current();

  /**
   * Applies a new routing config after validation.
   *
   * @throws IllegalArgumentException if the config is invalid
   */
  void update(RoutingConfig config);
}
