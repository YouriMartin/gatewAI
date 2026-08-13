package io.github.yourimartin.gatewai.domain.model;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cuts a prompt into the pieces an occlusion attribution reasons about
 * (v2 batch 7).
 *
 * <p>Four passes, each one refining the ranges of the previous: line breaks
 * (a bullet list is a list of ideas), then sentences, then any sentence
 * terminator the sentence pass ignored, then clauses inside a segment too long
 * to explain anything on its own.
 *
 * <p>The third pass exists because the JDK's {@link BreakIterator} — which is
 * what a locale-aware sentence split is on a JVM without ICU — only breaks
 * before a capital letter. "refactor this service. add tests." is one sentence
 * to it, and lowercase prompts are exactly what an LLM gateway receives. Its
 * other quirk is the opposite one: "Ask Dr. Martin" breaks after the
 * abbreviation. That imprecision is left standing rather than patched with a
 * list of abbreviations per language — one extra boundary costs one extra
 * segment, which the cap and the grouping absorb, and an attribution over
 * slightly wrong boundaries is still an attribution.
 *
 * <p>Above the cap, adjacent segments are <b>grouped</b> rather than dropped.
 * Every character of the prompt stays inside exactly one segment, so the
 * attributions describe the whole prompt and not a sample of it. The cap is what
 * bounds the cost: attribution costs one embedding per segment plus one, and
 * those calls land on the same local model the gateway serves requests with.
 */
public final class PromptSegmentation {

  /** Cut points inside an over-long segment, in the order they appear. */
  private static final String CLAUSE_DELIMITERS = ",;:—–()";

  /** Sentence terminators, for the pass that catches lowercase starts. */
  private static final String TERMINATORS = ".!?…";

  /**
   * Shortest piece that pass 3 will create. Without it, "e.g. this" and "v1.2"
   * would each become two segments of no explanatory value.
   */
  private static final int MIN_TERMINATED_PIECE = 4;

  private PromptSegmentation() {
  }

  /**
   * Segments {@code prompt} into at most {@code maxSegments} pieces.
   *
   * @param prompt          the text to cut; blank yields no segments
   * @param maxSegments     hard cap, grouping adjacent pieces to respect it
   * @param maxSegmentChars length past which a segment is cut into clauses
   */
  public static List<PromptSegment> segment(String prompt, int maxSegments,
                                            int maxSegmentChars) {
    if (prompt == null || prompt.isBlank()) {
      return List.of();
    }
    if (maxSegments < 1) {
      throw new IllegalArgumentException("maxSegments must be >= 1");
    }

    List<int[]> ranges = lines(prompt);
    ranges = refine(prompt, ranges, PromptSegmentation::sentences);
    ranges = refine(prompt, ranges, PromptSegmentation::terminatedPieces);
    ranges = refine(prompt, ranges,
        (text, range) -> clauses(text, range, maxSegmentChars));

    List<PromptSegment> segments = new ArrayList<>(ranges.size());
    for (int[] range : ranges) {
      segments.add(new PromptSegment(
          prompt.substring(range[0], range[1]), range[0], range[1]));
    }
    return group(prompt, segments, maxSegments);
  }

  /** Applies one splitting pass to every range, keeping the order. */
  private static List<int[]> refine(String prompt, List<int[]> ranges,
                                    Splitter splitter) {
    List<int[]> refined = new ArrayList<>(ranges.size());
    for (int[] range : ranges) {
      for (int[] piece : splitter.split(prompt, range)) {
        int[] trimmed = trim(prompt, piece[0], piece[1]);
        if (trimmed != null) {
          refined.add(trimmed);
        }
      }
    }
    return refined;
  }

  /** Pass 1: line breaks, which are boundaries no sentence rule would find. */
  private static List<int[]> lines(String prompt) {
    List<int[]> ranges = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < prompt.length(); i++) {
      if (prompt.charAt(i) == '\n') {
        ranges.add(new int[] {start, i});
        start = i + 1;
      }
    }
    ranges.add(new int[] {start, prompt.length()});
    return ranges;
  }

  /** Pass 2: sentences, as the JDK sees them. */
  private static List<int[]> sentences(String prompt, int[] range) {
    BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
    iterator.setText(prompt.substring(range[0], range[1]));

    List<int[]> ranges = new ArrayList<>();
    int start = iterator.first();
    for (int end = iterator.next(); end != BreakIterator.DONE;
         start = end, end = iterator.next()) {
      ranges.add(new int[] {range[0] + start, range[0] + end});
    }
    return ranges;
  }

  /**
   * Pass 3: terminators the sentence pass walked past — the lowercase-start
   * case, which is most of what a gateway is actually sent.
   */
  private static List<int[]> terminatedPieces(String prompt, int[] range) {
    List<int[]> ranges = new ArrayList<>();
    int cursor = range[0];
    for (int i = range[0]; i < range[1] - 1; i++) {
      boolean terminator = TERMINATORS.indexOf(prompt.charAt(i)) >= 0;
      boolean followedBySpace = Character.isWhitespace(prompt.charAt(i + 1));
      if (terminator && followedBySpace
          && i + 1 - cursor >= MIN_TERMINATED_PIECE
          && range[1] - (i + 1) >= MIN_TERMINATED_PIECE) {
        ranges.add(new int[] {cursor, i + 1});
        cursor = i + 1;
      }
    }
    ranges.add(new int[] {cursor, range[1]});
    return ranges;
  }

  /**
   * Pass 4: clauses, for a segment longer than {@code maxSegmentChars}. A clause
   * that is still too long is kept whole: an arbitrary cut mid-word would
   * attribute a contribution to a fragment no reader could act on.
   */
  private static List<int[]> clauses(String prompt, int[] range,
                                     int maxSegmentChars) {
    if (range[1] - range[0] <= maxSegmentChars) {
      return List.of(range);
    }

    List<int[]> ranges = new ArrayList<>();
    int cursor = range[0];
    for (int i = range[0]; i < range[1]; i++) {
      if (CLAUSE_DELIMITERS.indexOf(prompt.charAt(i)) >= 0
          && i - cursor + 1 >= minimumClause(maxSegmentChars)) {
        ranges.add(new int[] {cursor, i + 1});
        cursor = i + 1;
      }
    }
    ranges.add(new int[] {cursor, range[1]});
    return ranges;
  }

  /**
   * Groups adjacent segments until at most {@code maxSegments} remain, in
   * balanced runs, so the cap costs resolution rather than coverage.
   */
  private static List<PromptSegment> group(String prompt,
                                           List<PromptSegment> segments,
                                           int maxSegments) {
    if (segments.size() <= maxSegments) {
      return List.copyOf(segments);
    }

    int perGroup = (segments.size() + maxSegments - 1) / maxSegments;
    List<PromptSegment> grouped = new ArrayList<>(maxSegments);
    for (int i = 0; i < segments.size(); i += perGroup) {
      PromptSegment first = segments.get(i);
      PromptSegment last = segments.get(Math.min(i + perGroup, segments.size()) - 1);
      grouped.add(new PromptSegment(
          prompt.substring(first.start(), last.end()), first.start(), last.end()));
    }
    return List.copyOf(grouped);
  }

  /** Keeps clauses from degenerating into one segment per comma. */
  private static int minimumClause(int maxSegmentChars) {
    return Math.max(1, maxSegmentChars / 4);
  }

  /** Bounds without surrounding whitespace, or null when nothing is left. */
  private static int[] trim(String prompt, int start, int end) {
    int from = start;
    int to = end;
    while (from < to && Character.isWhitespace(prompt.charAt(from))) {
      from++;
    }
    while (to > from && Character.isWhitespace(prompt.charAt(to - 1))) {
      to--;
    }
    return from == to ? null : new int[] {from, to};
  }

  /** One splitting pass over a range of the prompt. */
  @FunctionalInterface
  private interface Splitter {
    List<int[]> split(String prompt, int[] range);
  }
}
