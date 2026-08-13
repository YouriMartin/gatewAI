package io.github.yourimartin.gatewai.domain.model;

/**
 * One piece of a prompt, with where it sits in the original text (v2 batch 7).
 *
 * <p>Offsets rather than text alone, because occlusion needs the prompt
 * <b>minus</b> this piece, and rebuilding that from a substring search would
 * remove the wrong copy whenever a sentence repeats.
 *
 * @param text  the segment as it appears in the prompt
 * @param start inclusive offset in the prompt
 * @param end   exclusive offset in the prompt
 */
public record PromptSegment(String text, int start, int end) {

  public PromptSegment {
    if (start < 0 || end < start) {
      throw new IllegalArgumentException(
          "invalid segment bounds: [" + start + ", " + end + ")");
    }
  }

  /**
   * The prompt with this segment taken out — the occluded variant whose loss of
   * similarity is this segment's contribution.
   *
   * <p>Whitespace around the cut is collapsed so the remaining text reads as a
   * prompt rather than as a prompt with a hole in it: an embedding model is not
   * indifferent to a double space, and the point of the comparison is what the
   * <em>words</em> contributed.
   */
  public String occlude(String prompt) {
    String remainder = prompt.substring(0, start) + prompt.substring(end);
    return remainder.replaceAll("\\s{2,}", " ").strip();
  }
}
