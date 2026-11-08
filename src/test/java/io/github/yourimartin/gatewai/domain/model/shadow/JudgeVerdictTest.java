package io.github.yourimartin.gatewai.domain.model.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JudgeVerdictTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "YES|YES", "yes|YES", "Yes.|YES", "**YES**|YES", "YES, they match|YES",
      "NO|NO", "no.|NO", "No - different facts|NO",
      "UNSURE|UNSURE", "unsure|UNSURE"})
  void readsTheFirstWordOfTheReply(String reply, JudgeVerdict expected) {
    assertThat(JudgeVerdict.parse(reply)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {"Maybe", "I think so", "YESNO", "''", "42"})
  void anythingTheRubricDoesNotAllowIsUnsureNotAGuess(String reply) {
    assertThat(JudgeVerdict.parse(reply)).isEqualTo(JudgeVerdict.UNSURE);
  }

  @Test
  void surroundingWhitespaceIsIgnored() {
    assertThat(JudgeVerdict.parse("  NO\n")).isEqualTo(JudgeVerdict.NO);
  }

  @Test
  void noReplyIsUnsure() {
    assertThat(JudgeVerdict.parse(null)).isEqualTo(JudgeVerdict.UNSURE);
  }
}
