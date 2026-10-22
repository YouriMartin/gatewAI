package io.github.yourimartin.gatewai.domain.port.out;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;

/**
 * What the gateway has recently seen of each registry entry (ADR 0017, v4 B.5):
 * after enough consecutive transient failures an entry cools down, and is then
 * probed by one request.
 *
 * <p>Implementations must never throw and never block: health only orders a
 * request's candidates, it must not be able to fail one.
 */
public interface EgressHealth {

  /**
   * Whether {@code model} may be tried now. False while it cools down. Once the
   * cooldown has elapsed, true for <b>one</b> caller, whose attempt is the probe,
   * and false for the others until that attempt is reported.
   */
  boolean admit(ModelDefinition model);

  /** An attempt on {@code model} succeeded: its record is closed. */
  void succeeded(ModelDefinition model);

  /** An attempt on {@code model} failed for a transient reason. */
  void failed(ModelDefinition model);
}
