package io.github.yourimartin.gatewai.domain.model.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CacheDecisionReasonTest {

  @ParameterizedTest
  @EnumSource(PassThroughFeature.class)
  void everyPassThroughFeatureHasItsBypassReason(PassThroughFeature feature) {
    CacheDecisionReason reason = CacheDecisionReason.passThrough(feature);

    assertThat(reason.name()).isEqualTo("PASSTHROUGH_" + feature.name());
    // The column is varchar(64) (V10).
    assertThat(reason.name().length()).isLessThanOrEqualTo(64);
  }
}
