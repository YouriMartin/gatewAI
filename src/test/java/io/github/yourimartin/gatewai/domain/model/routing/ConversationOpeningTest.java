package io.github.yourimartin.gatewai.domain.model.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ConversationOpeningTest {

  private static final ConversationOpening.Turn SYSTEM = turn("system", "Be concise.");
  private static final ConversationOpening.Turn FIRST = turn("user", "Explain CAP");
  private static final ConversationOpening.Turn ANSWER = turn("assistant", "CAP says...");

  @Test
  void aFirstTurnAndItsSecondTurnShareOneFingerprint() {
    String turnOne = ConversationOpening.ofFirstTurn(List.of(SYSTEM, FIRST), "CAP says...")
        .orElseThrow().fingerprint();
    String turnTwo = ConversationOpening.ofHistory(List.of(SYSTEM, FIRST, ANSWER,
        turn("user", "and PACELC?"))).orElseThrow().fingerprint();
    String turnThree = ConversationOpening.ofHistory(List.of(SYSTEM, FIRST, ANSWER,
        turn("user", "and PACELC?"), turn("assistant", "PACELC adds..."),
        turn("user", "thanks"))).orElseThrow().fingerprint();

    assertEquals(turnOne, turnTwo);
    assertEquals(turnOne, turnThree);
  }

  @Test
  void eachPartOfTheOpeningChangesTheFingerprint() {
    String base = history(SYSTEM, FIRST, ANSWER);
    assertNotEquals(base, history(turn("system", "Be verbose."), FIRST, ANSWER));
    assertNotEquals(base, history(SYSTEM, turn("user", "Explain ACID"), ANSWER));
    assertNotEquals(base, history(SYSTEM, FIRST, turn("assistant", "Another answer")));
    assertNotEquals(base, history(FIRST, ANSWER), "dropping the system prompt is another opening");
  }

  @Test
  void aSystemMessageAddedLaterDoesNotChangeTheOpening() {
    assertEquals(history(SYSTEM, FIRST, ANSWER),
        history(SYSTEM, FIRST, ANSWER, turn("system", "Today is Monday."), turn("user", "ok")));
  }

  @Test
  void fieldsCannotBeShiftedIntoOneAnother() {
    assertNotEquals(history(turn("system", "ab"), turn("user", "c"), ANSWER),
        history(turn("system", "a"), turn("user", "bc"), ANSWER));
  }

  @Test
  void aRequestWithoutAnAnswerHasNoHistoryAndOneWithAnAnswerIsNoFirstTurn() {
    assertFalse(ConversationOpening.hasHistory(List.of(SYSTEM, FIRST)));
    assertTrue(ConversationOpening.ofHistory(List.of(SYSTEM, FIRST)).isEmpty());
    assertTrue(ConversationOpening.ofFirstTurn(List.of(FIRST, ANSWER, FIRST), "x").isEmpty());
    assertTrue(ConversationOpening.ofFirstTurn(List.of(FIRST), "").isEmpty());
    assertTrue(ConversationOpening.ofFirstTurn(List.of(SYSTEM), "x").isEmpty());
  }

  @Test
  void itIsAHexSha256AndNeverText() {
    String fingerprint = history(SYSTEM, FIRST, ANSWER);
    assertTrue(fingerprint.matches("[0-9a-f]{64}"));
    assertEquals("Explain CAP",
        ConversationOpening.ofHistory(List.of(SYSTEM, FIRST, ANSWER)).orElseThrow()
            .firstUserText());
  }

  @Test
  void theEncodingIsPinned() {
    // Every stored record is keyed on this hash. Computed independently.
    assertEquals("4caebbe6e14b906d7f305eb6c38bb4a84f664eb5e60c458e159626fa147878f7",
        history(SYSTEM, FIRST, ANSWER));
  }

  private static String history(ConversationOpening.Turn... turns) {
    return ConversationOpening.ofHistory(List.of(turns)).orElseThrow().fingerprint();
  }

  private static ConversationOpening.Turn turn(String role, String text) {
    return new ConversationOpening.Turn(role, text);
  }
}
