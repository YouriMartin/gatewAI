package io.github.yourimartin.gatewai.domain.model.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PassThroughRequestTest {

  @Test
  void aRequestTheChainCanHonourNeverTakesThePassThroughPath() {
    LlmRequest plain = new LlmRequest("auto", List.of(new LlmMessage("user", "Hi")), null, null);

    assertThatThrownBy(() -> new PassThroughRequest(plain, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aShadowRequestTakesItWhetherOrNotTheChainCouldHonourIt() {
    LlmRequest plain = new LlmRequest("auto", List.of(new LlmMessage("user", "Hi")), null, null);
    LlmRequest tools = new LlmRequest("auto", List.of(new LlmMessage("user", "Hi")), null, null,
        null, null, SamplingParameters.NONE, Set.of(PassThroughFeature.TOOLS));

    assertThat(PassThroughRequest.shadow(plain, "{}").evaluatedInShadow()).isTrue();
    // The chain would have passed this one through too: nothing to evaluate.
    assertThat(PassThroughRequest.shadow(tools, "{}").evaluatedInShadow()).isFalse();
    assertThat(new PassThroughRequest(tools, "{}").evaluatedInShadow()).isFalse();
  }
}
