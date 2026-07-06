package io.github.yourimartin.gatewai.domain.model;

import java.util.List;

/**
 * Runtime-tunable routing rules (Phase 5.2): which classification strategy to
 * use and the heuristic thresholds / keywords. Edited live via the admin API.
 *
 * @param strategy               classifier strategy: {@code heuristic} or {@code llm}
 * @param entryLengthThreshold   text longer than this (chars) routes >= entry
 * @param premiumLengthThreshold text longer than this (chars) routes to premium
 * @param premiumKeywords        substrings that force the premium tier
 */
public record RoutingConfig(
    String strategy,
    int entryLengthThreshold,
    int premiumLengthThreshold,
    List<String> premiumKeywords
) {

  public RoutingConfig {
    premiumKeywords =
        premiumKeywords == null ? List.of() : List.copyOf(premiumKeywords);
  }
}
