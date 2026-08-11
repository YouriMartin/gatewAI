package io.github.yourimartin.gatewai.domain.model;

/** Whether a stored calibration still describes the system in force. */
public enum CalibrationStatus {

  /** Calibrated, and against the model and rules currently running. */
  VALID,

  /**
   * Calibrated against something else — another embedding model, or routing
   * rules that have since been edited. The threshold it computed no longer
   * describes this system, so it is not applied.
   */
  STALE,

  /** Never calibrated. The fixed configured threshold applies. */
  ABSENT,

  /**
   * Calibrated, current, and deliberately not applied
   * ({@code gatewai.conformal.enabled=false}) — the switch back to fixed
   * thresholds that does not require deleting the work.
   */
  DISABLED
}
