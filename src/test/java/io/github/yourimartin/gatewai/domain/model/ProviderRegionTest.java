package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Where a provider runs, and how well that is known (v3 lot C.2). */
class ProviderRegionTest {

  @Test
  void carriesRegionProvenanceAndPue() {
    ProviderRegion region = new ProviderRegion(
        "bedrock", "us-east-1", RegionProvenance.KNOWN, 1.12);

    assertEquals("bedrock", region.provider());
    assertEquals("us-east-1", region.region());
    assertEquals(RegionProvenance.KNOWN, region.provenance());
    assertEquals(1.12, region.pue());
    assertTrue(region.isDeclared());
  }

  @Test
  void anUndeclaredProvenanceIsAssumedRatherThanKnown() {
    ProviderRegion region = new ProviderRegion("anthropic", "US-MIDA-PJM", null, null);

    // A region only becomes a fact when the operator says so.
    assertEquals(RegionProvenance.ASSUMED, region.provenance());
    assertEquals("assumed", region.provenance().label());
  }

  @Test
  void aMissingPueIsCarriedAsUndeclaredNotAsOne() {
    ProviderRegion region = new ProviderRegion("openai", "US-MIDA-PJM", null, null);

    // 1.0 would silently claim a datacenter with zero overhead; null says nothing.
    assertEquals(null, region.pue());
  }

  @Test
  void aPueBelowOneIsPhysicallyImpossible() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new ProviderRegion("vllm", "eu-west-3", RegionProvenance.KNOWN, 0.9));

    assertTrue(error.getMessage().contains("below 1.0"), error.getMessage());
  }

  @Test
  void blankRegionCountsAsUndeclared() {
    assertFalse(new ProviderRegion("openai", "  ", null, null).isDeclared());
    assertFalse(new ProviderRegion("openai", null, null, null).isDeclared());
  }
}
