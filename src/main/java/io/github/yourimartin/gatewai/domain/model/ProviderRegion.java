package io.github.yourimartin.gatewai.domain.model;

/**
 * Where a provider instance's compute runs, and how well that is known
 * (v3 lot C.2).
 *
 * <p>Region belongs to the <b>provider instance</b>, not to the model: every
 * model behind {@code gatewai.providers.anthropic} runs wherever Anthropic runs.
 * A gateway configured for France must not book a US-served call at France's
 * grid intensity, which is what lot C.3 uses this for.
 *
 * <p>Pure domain, zero framework dependencies. One instance exists per declared
 * provider instance, whether or not it declared a region — {@link #isDeclared()}
 * answers that, and {@code operatorControlled} is meaningful either way.
 *
 * @param provider   provider instance name ({@code gatewai.providers.<name>})
 * @param region     cloud region id ({@code us-east-1}) or grid zone id
 *                   ({@code FR}); {@code null} when none was declared
 * @param provenance {@code null} is read as {@link RegionProvenance#ASSUMED} —
 *                   a region is only a fact when the operator says it is
 * @param pue        power usage effectiveness of the hosting datacenter, or
 *                   {@code null} when undeclared (lot C.4 decides what a missing
 *                   PUE costs; it is carried, not applied, here)
 * @param operatorControlled whether the operator places this workload themselves.
 *                   Only then may a dispatch-chosen zone override the region
 *                   (lot C.3): deferring a job does not move a hosted API's
 *                   compute. Derived from the provider type by the adapter, so the
 *                   domain never learns what {@code openai-compatible} means
 */
public record ProviderRegion(
    String provider,
    String region,
    RegionProvenance provenance,
    Double pue,
    boolean operatorControlled
) {

  /** Lowest physically possible PUE: a datacenter cannot use less than it draws. */
  private static final double MIN_PUE = 1.0;

  public ProviderRegion {
    if (provenance == null) {
      provenance = RegionProvenance.ASSUMED;
    }
    if (pue != null && pue < MIN_PUE) {
      throw new IllegalArgumentException(
          "Provider '" + provider + "' declares pue=" + pue
              + ", which is below 1.0 — a datacenter cannot deliver more energy to "
              + "its servers than it draws from the grid.");
    }
  }

  /** Whether a region was declared at all. */
  public boolean isDeclared() {
    return region != null && !region.isBlank();
  }
}
