package com.example.gatewai.domain.port.out;

import com.example.gatewai.domain.model.RoutingConfig;

/**
 * Reads/applies the live routing configuration. Implemented by the adapter that
 * owns the runtime-mutable classifier settings.
 */
public interface RoutingConfigPort {

  RoutingConfig get();

  void update(RoutingConfig config);
}
