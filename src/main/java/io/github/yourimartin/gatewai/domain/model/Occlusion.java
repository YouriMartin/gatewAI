package io.github.yourimartin.gatewai.domain.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns occluded similarities into ranked attributions (v2 batch 7).
 *
 * <p>The whole method in one line: a segment's contribution is how much
 * similarity the prompt <em>loses</em> when that segment is taken out. Nothing
 * here embeds anything — the arithmetic is separated from the embedding calls so
 * it can be tested exactly, on numbers chosen by hand, rather than inferred from
 * a model's output.
 *
 * <p><b>Known limit, and it is not small</b>: this assumes contributions are
 * approximately additive, which is strictly false for a contextual encoder —
 * removing "not" changes what every other word means. Occlusion is a useful
 * approximation of which words carried the decision, not a decomposition of it.
 * Stated again in {@code docs/functional/limitations.md}, because a number that
 * looks like a percentage invites more trust than this one deserves.
 */
public final class Occlusion {

  private Occlusion() {
  }

  /**
   * Attributes {@code similarity} across {@code segments}.
   *
   * @param similarity          similarity of the whole prompt to the matched
   *                            utterance
   * @param segments            the segments, in prompt order
   * @param occludedSimilarity  similarity of the prompt without each segment,
   *                            positionally aligned with {@code segments}
   * @return attributions, strongest first
   */
  public static List<SegmentAttribution> attribute(
      double similarity, List<PromptSegment> segments,
      List<Double> occludedSimilarity) {

    if (segments == null || segments.isEmpty()) {
      return List.of();
    }
    if (occludedSimilarity == null
        || occludedSimilarity.size() != segments.size()) {
      throw new IllegalArgumentException(
          "every segment needs its occluded similarity: " + segments.size()
              + " segments, "
              + (occludedSimilarity == null ? 0 : occludedSimilarity.size())
              + " similarities");
    }

    List<SegmentAttribution> attributions = new ArrayList<>(segments.size());
    double positiveTotal = 0;
    for (int i = 0; i < segments.size(); i++) {
      double contribution = similarity - occludedSimilarity.get(i);
      positiveTotal += Math.max(contribution, 0);
      attributions.add(new SegmentAttribution(
          segments.get(i).text(), contribution, 0, 0));
    }

    attributions.sort(Comparator.comparingDouble(
        SegmentAttribution::contribution).reversed());

    List<SegmentAttribution> ranked = new ArrayList<>(attributions.size());
    for (int i = 0; i < attributions.size(); i++) {
      SegmentAttribution attribution = attributions.get(i);
      // Shares are computed over the positive contributions only: a segment that
      // pulled away from the route did not take a negative slice of the reason,
      // it simply was not part of it.
      double share = positiveTotal == 0
          ? 0 : Math.max(attribution.contribution(), 0) / positiveTotal;
      ranked.add(new SegmentAttribution(attribution.segment(),
          attribution.contribution(), share, i + 1));
    }
    return List.copyOf(ranked);
  }
}
