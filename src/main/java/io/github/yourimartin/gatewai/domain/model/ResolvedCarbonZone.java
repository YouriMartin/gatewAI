package io.github.yourimartin.gatewai.domain.model;

/**
 * The grid zone an inference is booked at, and where that zone came from
 * (v3 lot C.3).
 *
 * <p>{@code dispatchZone} is kept even when it was <b>not</b> applied: for a
 * hosted API the zone carbon-aware dispatch picked is <em>recorded, not
 * applied</em>, because deferring a job moves nothing in someone else's
 * datacenter. Keeping both makes that distinction legible afterwards instead of
 * turning it into a footnote.
 *
 * @param zone         grid zone id, or {@code null} for
 *                     {@link CarbonZoneSource#GATEWAY_DEFAULT} — the caller then
 *                     asks the intensity provider for its own default
 * @param source       which step of the chain supplied {@link #zone()}
 * @param dispatchZone the zone dispatch chose, or {@code null} outside a deferred
 *                     execution; present-but-unapplied is the hosted-API case
 */
public record ResolvedCarbonZone(
    String zone,
    CarbonZoneSource source,
    String dispatchZone
) {

  /** The gateway's own default intensity, with no zone attribution. */
  public static ResolvedCarbonZone gatewayDefault(String dispatchZone) {
    return new ResolvedCarbonZone(null, CarbonZoneSource.GATEWAY_DEFAULT, dispatchZone);
  }

  public ResolvedCarbonZone {
    if (source == null) {
      throw new IllegalArgumentException("a resolved zone must say where it came from");
    }
  }

  /** Whether the dispatch-chosen zone is the one the emissions are booked at. */
  public boolean dispatchApplied() {
    return source == CarbonZoneSource.DISPATCH;
  }

  /**
   * Whether dispatch chose a zone that was deliberately not applied — the
   * accounting-versus-physical gap, made explicit.
   */
  public boolean dispatchRecordedOnly() {
    return dispatchZone != null && !dispatchApplied();
  }
}
