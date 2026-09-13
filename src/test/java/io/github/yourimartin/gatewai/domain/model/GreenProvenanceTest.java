package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What a stored row says about itself (v3 lot C.5). */
class GreenProvenanceTest {

  private static GreenProvenance hostedUs(String dispatchZone,
                                          CarbonZoneSource source) {
    return new GreenProvenance("anthropic", "US-MIDA-PJM", 350.0, source,
        dispatchZone, RegionProvenance.ASSUMED, EnergySource.MODELLED, 1.12);
  }

  @Test
  void carriesEverythingNeededToCheckTheNumber() {
    GreenProvenance provenance = hostedUs(null, CarbonZoneSource.PROVIDER_REGION);

    assertEquals("anthropic", provenance.provider());
    assertEquals("US-MIDA-PJM", provenance.gridZone());
    assertEquals(350.0, provenance.gridIntensityGramsPerKwh());
    assertEquals(CarbonZoneSource.PROVIDER_REGION, provenance.gridZoneSource());
    assertEquals(EnergySource.MODELLED, provenance.energySource());
    assertEquals(1.12, provenance.pue());
  }

  @Test
  void anAssumedRegionSaysSo() {
    assertTrue(hostedUs(null, CarbonZoneSource.PROVIDER_REGION).regionAssumed());

    GreenProvenance known = new GreenProvenance("vllm", "FR", 56.0,
        CarbonZoneSource.PROVIDER_REGION, null, RegionProvenance.KNOWN,
        EnergySource.MODELLED, 1.15);
    assertFalse(known.regionAssumed());
  }

  @Test
  void aDispatchZoneThatWasNotAppliedIsVisibleAfterTheFact() {
    // The C.3 promise: deferring a job did not move Anthropic's compute, and the row
    // is where a reader can tell recorded from applied.
    GreenProvenance recorded = hostedUs("SE", CarbonZoneSource.PROVIDER_REGION);
    GreenProvenance applied = new GreenProvenance("vllm", "SE", 30.0,
        CarbonZoneSource.DISPATCH, "SE", RegionProvenance.KNOWN,
        EnergySource.MODELLED, null);

    assertTrue(recorded.dispatchRecordedOnly());
    assertFalse(applied.dispatchRecordedOnly());
  }

  @Test
  void aRowWithNoZoneReportsUnderTheUnattributedBucket() {
    GreenProvenance gatewayDefault = new GreenProvenance("ollama", null, 230.0,
        CarbonZoneSource.GATEWAY_DEFAULT, null, null, EnergySource.NOT_ACCOUNTED, null);

    assertEquals(GreenProvenance.UNATTRIBUTED_ZONE, gatewayDefault.reportingZone());
    assertEquals("ollama", gatewayDefault.reportingProvider());
    assertFalse(gatewayDefault.regionAssumed());
  }

  @Test
  void aPreLotC5RowComesBackUnknownRatherThanFabricated() {
    GreenProvenance unknown = GreenProvenance.UNKNOWN;

    assertEquals(GreenProvenance.UNATTRIBUTED_ZONE, unknown.reportingZone());
    assertEquals("unknown", unknown.reportingProvider());
    assertEquals(EnergySource.NOT_ACCOUNTED, unknown.energySource());
    assertEquals(CarbonZoneSource.GATEWAY_DEFAULT, unknown.gridZoneSource());
    assertFalse(unknown.regionAssumed());
  }

  @Test
  void nullLabelsAreNormalisedRatherThanLeftToBlowUpARenderer() {
    GreenProvenance provenance =
        new GreenProvenance(null, null, 0.0, null, null, null, null, null);

    assertEquals(CarbonZoneSource.GATEWAY_DEFAULT, provenance.gridZoneSource());
    assertEquals(EnergySource.NOT_ACCOUNTED, provenance.energySource());
  }
}
