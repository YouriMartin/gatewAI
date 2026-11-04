package io.github.yourimartin.gatewai.domain.model.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;

import org.junit.jupiter.api.Test;

class JudgeRubricTest {

  @Test
  void thePromptCarriesTheRubricTheConversationAndBothAnswersInOrder() {
    List<LlmMessage> prompt = JudgeRubric.prompt(List.of(
            new LlmMessage("system", "Be brief."), new LlmMessage("user", "What is 2+2?")),
        "4", "Four.");

    assertThat(prompt).hasSize(2);
    assertThat(prompt.get(0).role()).isEqualTo("system");
    assertThat(prompt.get(0).content()).contains("YES, NO or UNSURE");
    String body = prompt.get(1).content();
    assertThat(body).contains("[system] Be brief.").contains("[user] What is 2+2?");
    assertThat(body.indexOf("ANSWER A\n4")).isLessThan(body.indexOf("ANSWER B\nFour."));
  }

  @Test
  void isVersioned() {
    assertThat(JudgeRubric.VERSION).isEqualTo("would-hit-v1");
  }
}
