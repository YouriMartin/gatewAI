package io.github.yourimartin.gatewai.domain.model;

import java.util.List;

/**
 * Which parts of a prompt carried its routing decision (v2 batch 7).
 *
 * <p>Explains <b>one</b> quantity and says which: the similarity between the
 * prompt and the closest example of the route that won. That is the number the
 * router decides with when the strategy is semantic, so decomposing it is
 * decomposing the decision — and naming the route and the utterance is what
 * stops the segment list from being read as an explanation of something else.
 *
 * @param status               whether segments were scored, or why not
 * @param route                the matched route, null unless {@code COMPUTED}
 * @param tier                 the tier that route maps to
 * @param matchedUtterance     the route example the prompt was closest to
 * @param similarity           the similarity being decomposed
 * @param segments             per-segment attributions, strongest first
 * @param embeddingModel       provenance: the model behind the vectors
 * @param routingConfigVersion provenance: the rules in force when it was
 *                             computed, so a report read later can be told to
 *                             describe rules that have since changed
 */
public record AttributionReport(
    AttributionStatus status,
    String route,
    ModelTier tier,
    String matchedUtterance,
    double similarity,
    List<SegmentAttribution> segments,
    String embeddingModel,
    String routingConfigVersion
) {

  public AttributionReport {
    segments = segments == null ? List.of() : List.copyOf(segments);
  }

  /** A report that explains why there is nothing to explain. */
  public static AttributionReport notComputed(AttributionStatus status,
                                              String embeddingModel,
                                              String routingConfigVersion) {
    return new AttributionReport(status, null, null, null, 0, List.of(),
        embeddingModel, routingConfigVersion);
  }
}
