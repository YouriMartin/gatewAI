package io.github.yourimartin.gatewai.domain.model.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class UnsupportedFeatureExceptionTest {

  @Test
  void aRefusedPathNamesTheFeaturesAndWhereTheyAreServed() {
    UnsupportedFeatureException e = new UnsupportedFeatureException(
        Set.of(PassThroughFeature.LOGIT_BIAS, PassThroughFeature.TOOLS));

    assertThat(e.features()).containsExactly(PassThroughFeature.TOOLS, PassThroughFeature.LOGIT_BIAS);
    assertThat(e.getMessage()).contains("tools, logit_bias")
        .contains("POST /v1/chat/completions");
  }

  @Test
  void aTargetThatWouldIgnoreAFeatureIsNamed() {
    UnsupportedFeatureException e = UnsupportedFeatureException.notHonouredBy(
        Set.of(PassThroughFeature.RESPONSE_FORMAT), "claude-opus-4-8",
        "Anthropic's OpenAI SDK compatibility layer documents it as ignored");

    assertThat(e.features()).containsExactly(PassThroughFeature.RESPONSE_FORMAT);
    assertThat(e.getMessage()).contains("response_format").contains("claude-opus-4-8")
        .contains("silently ignore").contains("Pin a model on another provider");
  }
}
