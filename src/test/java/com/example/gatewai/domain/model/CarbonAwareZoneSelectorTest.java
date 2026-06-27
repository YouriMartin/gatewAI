package com.example.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CarbonAwareZoneSelectorTest {

  private final CarbonAwareZoneSelector selector = new CarbonAwareZoneSelector();

  @Test
  void picksZoneWithLowestIntensity() {
    Map<String, Double> zones = new LinkedHashMap<>();
    zones.put("FR", 56.0);
    zones.put("SE", 30.0);
    zones.put("DE", 380.0);

    assertEquals(Optional.of("SE"), selector.greenest(zones));
  }

  @Test
  void singleZoneIsSelected() {
    assertEquals(Optional.of("FR"), selector.greenest(Map.of("FR", 56.0)));
  }

  @Test
  void emptyMapReturnsEmpty() {
    assertTrue(selector.greenest(Map.of()).isEmpty());
  }

  @Test
  void nullMapReturnsEmpty() {
    assertTrue(selector.greenest(null).isEmpty());
  }
}
