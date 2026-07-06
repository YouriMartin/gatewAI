package io.github.yourimartin.gatewai.domain.model;

import java.util.Map;
import java.util.Optional;

/**
 * Picks the greenest grid zone — the one with the lowest carbon intensity at
 * the moment of execution. Pure domain logic, the heart of geographic
 * carbon-aware routing (Phase 4.4).
 */
public final class CarbonAwareZoneSelector {

  /**
   * Selects the zone with the lowest carbon intensity.
   *
   * @param intensitiesByZone zone id → grid carbon intensity (gCO2/kWh)
   * @return the greenest zone, or empty when no zones are provided
   */
  public Optional<String> greenest(Map<String, Double> intensitiesByZone) {
    if (intensitiesByZone == null || intensitiesByZone.isEmpty()) {
      return Optional.empty();
    }
    return intensitiesByZone.entrySet().stream()
        .min(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey);
  }
}
