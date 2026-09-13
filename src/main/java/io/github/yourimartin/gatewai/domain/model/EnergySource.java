package io.github.yourimartin.gatewai.domain.model;

/**
 * Where a model's energy coefficient comes from — the provenance label that
 * turns a number into a claim a reader can check (v3 lot C.1).
 *
 * <p>The distinction that matters is {@link #NOT_ACCOUNTED}: a model whose
 * energy is <em>not measured and not estimated</em> contributes zero to the
 * totals, and every renderer must say <b>"excluded from scope"</b> rather than
 * print a bare {@code 0 gCO2}. Self-hosted (local) inference is deliberately in
 * that bucket for v3 — metering it needs host-level counters (RAPL / NVML) and
 * an offline calibration harness, which is a later lot.
 *
 * <p>Pure domain, zero framework dependencies. The label lives here so the JSON,
 * CSV, PDF and dashboard renderers all print the same words.
 */
public enum EnergySource {

  /** Not accounted: no coefficient, booked at zero, excluded from the totals. */
  NOT_ACCOUNTED("excluded from scope"),

  /** A figure published by the model vendor (highest credibility, rare). */
  VENDOR_PUBLISHED("vendor-published estimate"),

  /** A parametric estimate (EcoLogits / Boavizta-style), sourced and dated. */
  MODELLED("modelled estimate");

  private final String label;

  EnergySource(String label) {
    this.label = label;
  }

  /** Human-readable label, identical across every export format. */
  public String label() {
    return label;
  }

  /** Whether emissions from this model are included in the reported totals. */
  public boolean accounted() {
    return this != NOT_ACCOUNTED;
  }
}
