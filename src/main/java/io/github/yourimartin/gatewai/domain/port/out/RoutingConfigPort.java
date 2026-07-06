package io.github.yourimartin.gatewai.domain.port.out;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;

/**
 * Reads/applies the live routing configuration. Implemented by the adapter that
 * owns the runtime-mutable classifier settings.
 */
public interface RoutingConfigPort {

  RoutingConfig get();

  void update(RoutingConfig config);
}
