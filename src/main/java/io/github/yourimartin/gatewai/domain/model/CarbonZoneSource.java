package io.github.yourimartin.gatewai.domain.model;

/**
 * Which step of the resolution chain supplied the grid zone an inference was
 * booked at (v3 lot C.3). Stored with the row (lot C.5) so a figure can be
 * re-derived and a wrong attribution can be spotted.
 */
public enum CarbonZoneSource {

  /**
   * The zone carbon-aware dispatch picked. Applied only for providers the
   * operator controls — deferring a job does not move a hosted API's compute.
   */
  DISPATCH("dispatch-chosen zone"),

  /** The region declared on the provider instance ({@code gatewai.providers.*}). */
  PROVIDER_REGION("provider region"),

  /**
   * Nothing more specific was known, so the gateway's own default intensity
   * applies. Deliberately <b>not</b> a zone id: the gateway default lives behind
   * {@code CarbonIntensityProvider.gramsCo2PerKwh()}, and pretending it is a
   * named zone would change what that method returns.
   */
  GATEWAY_DEFAULT("gateway default");

  private final String label;

  CarbonZoneSource(String label) {
    this.label = label;
  }

  /** Human-readable label, identical across every export format. */
  public String label() {
    return label;
  }
}
