package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PromptSegmentationTest {

  private static final int MAX = 20;
  private static final int MAX_CHARS = 200;

  @Test
  @DisplayName("sentences are the natural unit")
  void splitsOnSentences() {
    List<PromptSegment> segments = PromptSegmentation.segment(
        "Refactor this service. Then write tests. Thanks!", MAX, MAX_CHARS);

    assertEquals(List.of("Refactor this service.", "Then write tests.",
        "Thanks!"), texts(segments));
  }

  @Test
  @DisplayName("lowercase sentences split too — that is most real traffic")
  void splitsSentencesThatDoNotStartWithACapital() {
    // The JDK's BreakIterator only breaks before a capital, so this whole
    // prompt is one sentence to it. Attribution over one segment says nothing.
    List<PromptSegment> segments = PromptSegmentation.segment(
        "refactor this service. add tests. ship it", MAX, MAX_CHARS);

    assertEquals(List.of("refactor this service.", "add tests.", "ship it"),
        texts(segments));
  }

  @Test
  @DisplayName("line breaks are boundaries no sentence rule would find")
  void splitsOnLineBreaks() {
    List<PromptSegment> segments = PromptSegmentation.segment(
        "Fix the parser\nAdd a test\nUpdate the docs", MAX, MAX_CHARS);

    assertEquals(3, segments.size(), texts(segments).toString());
  }

  @Test
  @DisplayName("an abbreviation costs one extra boundary, and that is accepted")
  void abbreviationsAreAKnownImprecision() {
    // Documented rather than patched with per-language abbreviation lists: the
    // extra segment is absorbed by grouping, and the attribution still holds.
    List<PromptSegment> segments = PromptSegmentation.segment(
        "Ask Dr. Martin about the schema. Then report back.", MAX, MAX_CHARS);

    assertEquals(3, segments.size(), texts(segments).toString());
    assertEquals("Ask Dr.", segments.getFirst().text());
  }

  @Test
  @DisplayName("a version number is not a sentence boundary")
  void shortPiecesAreNotSplitOff() {
    List<PromptSegment> segments = PromptSegmentation.segment(
        "Upgrade to Spring Boot 4.0 today", MAX, MAX_CHARS);

    assertEquals(1, segments.size(), texts(segments).toString());
  }

  @Test
  @DisplayName("French text segments the same way")
  void worksInFrench() {
    List<PromptSegment> segments = PromptSegmentation.segment(
        "Analyse la complexité de cet algorithme. Propose une optimisation.",
        MAX, MAX_CHARS);

    assertEquals(2, segments.size());
  }

  @Test
  @DisplayName("an over-long sentence is cut at clause boundaries")
  void longSentencesAreCutIntoClauses() {
    String sentence = "Design a schema for a multi-tenant SaaS application, "
        + "explain the trade-offs between shared and isolated tables, "
        + "and justify the indexing strategy you would choose for it.";

    List<PromptSegment> segments =
        PromptSegmentation.segment(sentence, MAX, 60);

    assertTrue(segments.size() > 1, "one 170-char block explains nothing");
    assertTrue(texts(segments).stream()
        .allMatch(text -> !text.isBlank()));
  }

  @Test
  @DisplayName("segments cover the prompt: offsets are exact and ordered")
  void offsetsAreExact() {
    String prompt = "Refactor this service. Then write tests.";
    List<PromptSegment> segments =
        PromptSegmentation.segment(prompt, MAX, MAX_CHARS);

    int previousEnd = 0;
    for (PromptSegment segment : segments) {
      assertEquals(segment.text(),
          prompt.substring(segment.start(), segment.end()));
      assertTrue(segment.start() >= previousEnd);
      previousEnd = segment.end();
    }
  }

  @Test
  @DisplayName("above the cap, segments are grouped, never dropped")
  void groupsRatherThanTruncates() {
    String prompt = "Fix the parser. Add a test. Update the docs. "
        + "Then benchmark it. Finally, ship it.";

    List<PromptSegment> segments = PromptSegmentation.segment(prompt, 3, MAX_CHARS);

    assertEquals(3, segments.size());
    assertEquals(0, segments.getFirst().start());
    assertEquals(prompt.length(), segments.getLast().end(),
        "the tail of the prompt must still be attributed to something");
  }

  @Test
  @DisplayName("occlusion removes exactly one segment, even when it repeats")
  void occlusionUsesOffsetsNotSearch() {
    String prompt = "Fix it. Fix it. Ship it.";
    List<PromptSegment> segments =
        PromptSegmentation.segment(prompt, MAX, MAX_CHARS);

    assertEquals("Fix it. Ship it.", segments.getFirst().occlude(prompt));
    assertEquals("Fix it. Fix it.", segments.getLast().occlude(prompt));
  }

  @Test
  @DisplayName("occluding the only segment leaves nothing")
  void occludingEverything() {
    String prompt = "Hello there.";
    PromptSegment only =
        PromptSegmentation.segment(prompt, MAX, MAX_CHARS).getFirst();

    assertTrue(only.occlude(prompt).isEmpty());
  }

  @Test
  @DisplayName("nothing to segment")
  void blankPrompts() {
    assertTrue(PromptSegmentation.segment(null, MAX, MAX_CHARS).isEmpty());
    assertTrue(PromptSegmentation.segment("   ", MAX, MAX_CHARS).isEmpty());
  }

  @Test
  @DisplayName("a cap below one is a configuration error, not a silent default")
  void rejectsAnImpossibleCap() {
    assertThrows(IllegalArgumentException.class, () ->
        PromptSegmentation.segment("Hello.", 0, MAX_CHARS));
  }

  private static List<String> texts(List<PromptSegment> segments) {
    return segments.stream().map(PromptSegment::text).toList();
  }
}
