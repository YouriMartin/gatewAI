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

  /**
   * The cascade's ambiguity band (v2 batch 4). Read and written apart from the
   * config because it is not part of {@code routing_config_version} — see
   * {@code RoutingConfigPort#cascadeMarginBand()}.
   */
  double cascadeMarginBand();

  /**
   * @throws IllegalArgumentException if the band is outside {@code [0, 1]}
   */
  void updateCascadeMarginBand(double band);
}
