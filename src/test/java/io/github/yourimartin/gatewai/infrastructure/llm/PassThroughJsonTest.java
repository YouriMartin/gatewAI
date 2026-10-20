package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;

import org.junit.jupiter.api.Test;

class PassThroughJsonTest {

  @Test
  void rewritesOnlyTheModel() {
    assertThat(PassThroughJson.withModel(
        "{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"model\":\"auto\",\"x\":null}",
        "qwen2.5:3b"))
        .isEqualTo("{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],"
            + "\"model\":\"qwen2.5:3b\",\"x\":null}");
  }

  @Test
  void addsTheModelWhenTheClientSentNone() {
    assertThat(PassThroughJson.withModel("{\"messages\":[]}", "m"))
        .isEqualTo("{\"messages\":[],\"model\":\"m\"}");
  }

  @Test
  void refusesABodyThatIsNotAnObject() {
    assertThatThrownBy(() -> PassThroughJson.withModel("[1]", "m"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsTheUsageAndKeepsTheReportedTotal() {
    assertThat(PassThroughJson.usage(
        "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":16}}"))
        .isEqualTo(new TokenUsage(10, 5, 16));
  }

  @Test
  void aMissingTotalIsTheSumOfThePhases() {
    assertThat(PassThroughJson.usage("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"))
        .isEqualTo(new TokenUsage(10, 5, 15));
  }

  @Test
  void noUsageIsNullNotZero() {
    assertThat(PassThroughJson.usage("{\"choices\":[]}")).isNull();
    assertThat(PassThroughJson.usage("{\"usage\":null}")).isNull();
    assertThat(PassThroughJson.usage("not json")).isNull();
  }
}
