package io.github.yourimartin.gatewai.domain.model;

/**
 * How one row's green figures were arrived at (v3 lot C.5) — stored with the row so
 * it explains itself without consulting today's configuration.
 *
 * <p>Before C.5 a report could only be read against the registry as it stood when the
 * report ran: edit a coefficient or a region and every historical row silently changed
 * meaning. With this attached, {@code gramsCo2 = energyKwh × gridIntensityGramsPerKwh}
 * is checkable on the row itself, and the labels say what kind of estimate it was.
 *
 * <p>What it deliberately does <b>not</b> carry is the coefficient set: the stored
 * {@code energyKwh} is the output of the model in force at the time, so history is
 * immutable by construction, but re-deriving kWh from the two token counts would need
 * a snapshot of prefill/decode/fixed per row. That is a later step, and
 * {@code docs/technical/green-accounting.md} says so.
 *
 * <p>Pure domain, zero framework dependencies.
 *
 * @param provider                 provider instance that served the request, or
 *                                 {@code null} when the model was not in the registry
 * @param gridZone                 grid zone the emissions were booked at, or
 *                                 {@code null} for the gateway's own default
 * @param gridIntensityGramsPerKwh the intensity actually applied, gCO2/kWh
 * @param gridZoneSource           which step of the chain supplied the zone (C.3)
 * @param dispatchZone             the zone carbon-aware dispatch chose, or
 *                                 {@code null}; present while {@code gridZoneSource}
 *                                 is not {@code DISPATCH} means <b>recorded and not
 *                                 applied</b> — a hosted API's compute did not move
 * @param regionProvenance         whether the provider's region was a fact or an
 *                                 operator assumption, or {@code null} when none was
 *                                 declared
 * @param energySource             provenance of the energy coefficients (C.1/C.4)
 * @param pue                      datacenter overhead factor already inside
 *                                 {@code energyKwh}, or {@code null} when the
 *                                 documented default applied
 */
public record GreenProvenance(
    String provider,
    String gridZone,
    double gridIntensityGramsPerKwh,
    CarbonZoneSource gridZoneSource,
    String dispatchZone,
    RegionProvenance regionProvenance,
    EnergySource energySource,
    Double pue
) {

  /** Zone key used in reports for rows with no regional attribution at all. */
  public static final String UNATTRIBUTED_ZONE = "unattributed";

  /**
   * Nothing known: a request whose model is not in the registry, or a row written
   * before lot C.5 existed. Energy is reported as unaccounted rather than guessed.
   */
  public static final GreenProvenance UNKNOWN = new GreenProvenance(
      null, null, 0.0, CarbonZoneSource.GATEWAY_DEFAULT, null, null,
      EnergySource.NOT_ACCOUNTED, null);

  public GreenProvenance {
    if (gridZoneSource == null) {
      gridZoneSource = CarbonZoneSource.GATEWAY_DEFAULT;
    }
    if (energySource == null) {
      energySource = EnergySource.NOT_ACCOUNTED;
    }
  }

  /** Zone id for reporting: the real one, or the unattributed bucket. */
  public String reportingZone() {
    return gridZone == null || gridZone.isBlank() ? UNATTRIBUTED_ZONE : gridZone;
  }

  /** Provider name for reporting, never {@code null}. */
  public String reportingProvider() {
    return provider == null || provider.isBlank() ? "unknown" : provider;
  }

  /** Whether this row's region was declared but not actually known. */
  public boolean regionAssumed() {
    return regionProvenance == RegionProvenance.ASSUMED && gridZone != null;
  }

  /** Whether dispatch chose a zone that was deliberately not applied. */
  public boolean dispatchRecordedOnly() {
    return dispatchZone != null && gridZoneSource != CarbonZoneSource.DISPATCH;
  }
}
