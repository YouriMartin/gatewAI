package io.github.yourimartin.gatewai.domain.model;

/** Which decision a calibration governs (v2 batch 3). */
public enum CalibrationTarget {

  /** The semantic cache's accept threshold. */
  CACHE,

  /** The router's route-similarity threshold. */
  ROUTING
}
