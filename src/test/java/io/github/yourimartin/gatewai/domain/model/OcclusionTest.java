package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The occlusion arithmetic, on numbers chosen by hand. Separating it from the
 * embedding calls is what makes it checkable at all: with a real model in the
 * loop the expected values would have to be read off the model's output, which
 * proves nothing about the method.
 */
class OcclusionTest {

  @Test
  @DisplayName("a segment contributes what removing it costs")
  void contributionIsTheSimilarityLost() {
    List<SegmentAttribution> attributions = Occlusion.attribute(0.80,
        segments("Refactor this service.", "Thanks!"),
        List.of(0.50, 0.78));

    assertEquals("Refactor this service.", attributions.getFirst().segment());
    assertEquals(0.30, attributions.getFirst().contribution(), 1e-9);
    assertEquals(0.02, attributions.get(1).contribution(), 1e-9);
  }

  @Test
  @DisplayName("attributions are ranked strongest first")
  void strongestFirst() {
    List<SegmentAttribution> attributions = Occlusion.attribute(0.80,
        segments("weak", "strong", "middling"),
        List.of(0.79, 0.40, 0.70));

    assertEquals(List.of("strong", "middling", "weak"),
        attributions.stream().map(SegmentAttribution::segment).toList());
    assertEquals(List.of(1, 2, 3),
        attributions.stream().map(SegmentAttribution::rank).toList());
  }

  @Test
  @DisplayName("shares are a normalization of the positive contributions")
  void sharesSumToOne() {
    List<SegmentAttribution> attributions = Occlusion.attribute(0.80,
        segments("a", "b"), List.of(0.50, 0.70));

    assertEquals(0.75, attributions.getFirst().share(), 1e-9);
    assertEquals(0.25, attributions.get(1).share(), 1e-9);
    assertEquals(1.0, attributions.stream()
        .mapToDouble(SegmentAttribution::share).sum(), 1e-9);
  }

  @Test
  @DisplayName("a segment that pulled the other way keeps its negative sign")
  void negativeContributionsAreKept() {
    // Removing "please" made the prompt *more* like the route: it was pulling
    // away from it. That is a finding, not noise to be clamped away.
    List<SegmentAttribution> attributions = Occlusion.attribute(0.60,
        segments("Debug this stack trace", "please"),
        List.of(0.30, 0.65));

    SegmentAttribution pulling = attributions.get(1);
    assertEquals("please", pulling.segment());
    assertTrue(pulling.contribution() < 0);
    assertEquals(0, pulling.share(),
        "a segment that was not part of the reason takes no slice of it");
    assertEquals(1.0, attributions.getFirst().share(), 1e-9);
  }

  @Test
  @DisplayName("when nothing contributed, shares are zero rather than NaN")
  void noPositiveContributionMeansNoShares() {
    List<SegmentAttribution> attributions = Occlusion.attribute(0.60,
        segments("a", "b"), List.of(0.60, 0.65));

    assertTrue(attributions.stream()
        .allMatch(attribution -> attribution.share() == 0));
  }

  @Test
  @DisplayName("no segments, no attributions")
  void emptyInput() {
    assertTrue(Occlusion.attribute(0.9, List.of(), List.of()).isEmpty());
    assertTrue(Occlusion.attribute(0.9, null, null).isEmpty());
  }

  @Test
  @DisplayName("a missing occluded similarity is a bug, not a zero")
  void mismatchedInputsAreRejected() {
    assertThrows(IllegalArgumentException.class, () ->
        Occlusion.attribute(0.9, segments("a", "b"), List.of(0.5)));
  }

  private static List<PromptSegment> segments(String... texts) {
    List<PromptSegment> segments = new java.util.ArrayList<>();
    int offset = 0;
    for (String text : texts) {
      segments.add(new PromptSegment(text, offset, offset + text.length()));
      offset += text.length() + 1;
    }
    return segments;
  }
}
