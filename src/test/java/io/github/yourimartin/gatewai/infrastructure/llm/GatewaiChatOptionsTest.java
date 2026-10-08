package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;

/** The seed survives every rebuild the advisor chain makes of the options (v4 B.1). */
class GatewaiChatOptionsTest {

  private final GatewaiChatOptions options = GatewaiChatOptions.builder()
      .model("m").temperature(0.5).maxTokens(10).stopSequences(List.of("END"))
      .topP(0.9).presencePenalty(0.1).frequencyPenalty(0.2).seed(7L)
      .build();

  @Test
  void mutateKeepsEveryOption() {
    ChatOptions copy = options.mutate().build();

    assertThat(copy).isEqualTo(options);
    assertThat(GatewaiChatOptions.seedOf(copy)).isEqualTo(7L);
  }

  @Test
  void mergingIntoGatewayOptionsKeepsTheSeed() {
    ChatOptions merged = GatewaiChatOptions.builder().combineWith(options.mutate()).build();

    assertThat(merged).isEqualTo(options);
  }

  @Test
  void theSeedTakesPartInEquality() {
    assertThat(options.mutate().seed(8L).build()).isNotEqualTo(options);
  }

  @Test
  void portableOptionsHaveNoSeed() {
    assertThat(GatewaiChatOptions.seedOf(ChatOptions.builder().model("m").build())).isNull();
    assertThat(GatewaiChatOptions.seedOf(null)).isNull();
  }
}
