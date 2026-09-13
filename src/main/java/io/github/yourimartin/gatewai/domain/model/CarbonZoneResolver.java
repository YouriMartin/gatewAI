package io.github.yourimartin.gatewai.domain.model;

/**
 * Decides which grid zone one inference is booked at (v3 lot C.3). Pure domain
 * logic: the caller gathers the facts, this owns the precedence.
 *
 * <p>Before C.3 a single intensity — the gateway's own — priced every request, so
 * a gateway configured for France booked a Claude call, US compute, at France's
 * 56 gCO2/kWh. The chain, first hit wins:
 *
 * <ol>
 *   <li><b>dispatch-chosen zone</b>, but only for a provider the operator
 *       controls. Deferring a job does not move a hosted API's compute, so for
 *       Anthropic or OpenAI the chosen zone is <em>recorded and not applied</em>
 *       ({@link ResolvedCarbonZone#dispatchRecordedOnly()});</li>
 *   <li><b>the provider instance's region</b>, mapped to a grid zone by the
 *       caller ({@code CloudRegionZones});</li>
 *   <li><b>the gateway default</b> — no zone id, the intensity provider's own
 *       default value.</li>
 * </ol>
 *
 * <p>An unknown provider is treated as <b>not</b> controlled: a dispatch zone is
 * only applied to a workload the operator is known to place.
 */
public final class CarbonZoneResolver {

  /**
   * Resolves the zone for one inference.
   *
   * @param provider     what is known about the serving provider instance, or
   *                     {@code null} when the provider is not declared
   * @param providerZone {@code provider}'s region already mapped to a grid zone,
   *                     or {@code null} when it declared none or none could be
   *                     mapped
   * @param dispatchZone the zone carbon-aware dispatch picked, or {@code null} on
   *                     the normal synchronous path
   * @return the zone to account at and where it came from, never {@code null}
   */
  public ResolvedCarbonZone resolve(ProviderRegion provider,
                                    String providerZone,
                                    String dispatchZone) {
    String dispatch = blankToNull(dispatchZone);
    if (dispatch != null && provider != null && provider.operatorControlled()) {
      return new ResolvedCarbonZone(dispatch, CarbonZoneSource.DISPATCH, dispatch);
    }
    String region = blankToNull(providerZone);
    if (region != null) {
      return new ResolvedCarbonZone(region, CarbonZoneSource.PROVIDER_REGION, dispatch);
    }
    return ResolvedCarbonZone.gatewayDefault(dispatch);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
