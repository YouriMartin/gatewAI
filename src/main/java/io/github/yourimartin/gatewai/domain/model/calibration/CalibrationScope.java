package io.github.yourimartin.gatewai.domain.model.calibration;

/**
 * Which calibration decided a request (v4 D.3, ADR 0019): stored on every cache
 * decision, so the trace says which threshold applied without reconstructing it.
 */
public enum CalibrationScope {

  /** The client's own calibration, fitted on its shadow labels. */
  CLIENT,

  /** The global calibration every client without its own falls back to. */
  GLOBAL,

  /** No calibration: the fixed configured threshold decided. */
  NONE
}
