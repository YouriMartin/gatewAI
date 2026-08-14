package io.github.yourimartin.gatewai.domain.port.out;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;

/**
 * Reads/applies the live routing configuration. Implemented by the adapter that
 * owns the runtime-mutable classifier settings.
 */
public interface RoutingConfigPort {

  RoutingConfig get();

  void update(RoutingConfig config);

  /**
   * The cascade's ambiguity band, deliberately <b>outside</b>
   * {@link RoutingConfig} (v2 batch 4, D26).
   *
   * <p>Every other routing knob is part of the config, and therefore of
   * {@code routing_config_version}, whose job is to mark a conformal
   * calibration stale when the similarities it was fitted on stop describing the
   * system. The band changes no similarity — it only decides when to escalate —
   * so folding it in would force a refit every time an operator tuned a knob the
   * calibration does not depend on. It is read and written separately for
   * exactly that reason (v2 batch 9).
   */
  double cascadeMarginBand();

  void updateCascadeMarginBand(double band);
}
