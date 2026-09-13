package io.github.yourimartin.gatewai.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Emissions split by where and by whom (v3 lot C.5) — the view lot C exists to
 * produce. Built from what each row stored about itself, never from the registry as
 * it stands now, so a report of last quarter still describes last quarter.
 *
 * @param gramsCo2ByRegion   grid zone → gCO2; rows with no attribution land under
 *                           {@link GreenProvenance#UNATTRIBUTED_ZONE}
 * @param gramsCo2ByProvider provider instance → gCO2
 * @param assumedRegions     zones whose rows carried an <b>assumed</b> region — an
 *                           operator declaration, not a fact, and every export has
 *                           to say so
 */
public record EmissionsBreakdown(
    Map<String, Double> gramsCo2ByRegion,
    Map<String, Double> gramsCo2ByProvider,
    List<String> assumedRegions
) {

  /** Nothing to break down. */
  public static final EmissionsBreakdown EMPTY =
      new EmissionsBreakdown(Map.of(), Map.of(), List.of());

  public EmissionsBreakdown {
    gramsCo2ByRegion = gramsCo2ByRegion == null ? Map.of() : Map.copyOf(gramsCo2ByRegion);
    gramsCo2ByProvider =
        gramsCo2ByProvider == null ? Map.of() : Map.copyOf(gramsCo2ByProvider);
    // List.copyOf rather than the stream's own list: SpotBugs only recognises the
    // former as a defensive copy (EI_EXPOSE_REP), and a record accessor hands this
    // straight out.
    assumedRegions = assumedRegions == null ? List.of()
        : List.copyOf(assumedRegions.stream().sorted().distinct().toList());
  }

  /** Whether any region in this report was declared rather than known. */
  public boolean hasAssumedRegions() {
    return !assumedRegions.isEmpty();
  }
}
