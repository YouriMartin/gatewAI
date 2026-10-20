package io.github.yourimartin.gatewai.domain.model.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class PassThroughRequestTest {

  @Test
  void aRequestTheChainCanHonourNeverTakesThePassThroughPath() {
    LlmRequest plain = new LlmRequest("auto", List.of(new LlmMessage("user", "Hi")), null, null);

    assertThatThrownBy(() -> new PassThroughRequest(plain, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
