package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The precedence chain that decides which grid an inference is booked at
 * (v3 lot C.3): dispatch zone (controlled providers only) → provider region →
 * gateway default.
 */
class CarbonZoneResolverTest {

  private final CarbonZoneResolver resolver = new CarbonZoneResolver();

  private static ProviderRegion controlled() {
    return new ProviderRegion("vllm", "eu-west-3", RegionProvenance.KNOWN, 1.15, true);
  }

  private static ProviderRegion hosted() {
    return new ProviderRegion(
        "anthropic", "US-MIDA-PJM", RegionProvenance.ASSUMED, null, false);
  }

  @Test
  void dispatchWinsForAProviderTheOperatorControls() {
    ResolvedCarbonZone resolved = resolver.resolve(controlled(), "FR", "SE");

    assertEquals("SE", resolved.zone());
    assertEquals(CarbonZoneSource.DISPATCH, resolved.source());
    assertTrue(resolved.dispatchApplied());
    assertFalse(resolved.dispatchRecordedOnly());
  }

  @Test
  void dispatchIsRecordedButNotAppliedForAHostedApi() {
    ResolvedCarbonZone resolved = resolver.resolve(hosted(), "US-MIDA-PJM", "SE");

    // Deferring a job does not move Anthropic's compute: the emissions stay on the
    // provider's grid, and the zone dispatch picked is kept for the record.
    assertEquals("US-MIDA-PJM", resolved.zone());
    assertEquals(CarbonZoneSource.PROVIDER_REGION, resolved.source());
    assertEquals("SE", resolved.dispatchZone());
    assertFalse(resolved.dispatchApplied());
    assertTrue(resolved.dispatchRecordedOnly());
  }

  @Test
  void theProviderRegionIsUsedOnTheSynchronousPath() {
    ResolvedCarbonZone resolved = resolver.resolve(hosted(), "US-MIDA-PJM", null);

    assertEquals("US-MIDA-PJM", resolved.zone());
    assertEquals(CarbonZoneSource.PROVIDER_REGION, resolved.source());
    assertNull(resolved.dispatchZone());
    assertFalse(resolved.dispatchRecordedOnly());
  }

  @Test
  void withNoRegionAndNoDispatchTheGatewayDefaultApplies() {
    ResolvedCarbonZone resolved = resolver.resolve(
        new ProviderRegion("ollama", null, null, null, true), null, null);

    // Null zone on purpose: the intensity provider owns its own default, and naming
    // a zone here would change the value it returns.
    assertNull(resolved.zone());
    assertEquals(CarbonZoneSource.GATEWAY_DEFAULT, resolved.source());
  }

  @Test
  void anUnknownProviderNeverGetsTheDispatchZone() {
    ResolvedCarbonZone resolved = resolver.resolve(null, null, "SE");

    // A dispatch zone is only applied to a workload the operator is known to place.
    assertNull(resolved.zone());
    assertEquals(CarbonZoneSource.GATEWAY_DEFAULT, resolved.source());
    assertEquals("SE", resolved.dispatchZone());
    assertTrue(resolved.dispatchRecordedOnly());
  }

  @Test
  void blankZonesAreTreatedAsAbsentAndValuesAreTrimmed() {
    assertEquals(CarbonZoneSource.GATEWAY_DEFAULT,
        resolver.resolve(controlled(), "  ", "  ").source());
    assertEquals("SE", resolver.resolve(controlled(), null, " SE ").zone());
    assertEquals("FR", resolver.resolve(hosted(), " FR ", null).zone());
  }

  @Test
  void aResolvedZoneMustSayWhereItCameFrom() {
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> new ResolvedCarbonZone("FR", null, null));
  }
}
