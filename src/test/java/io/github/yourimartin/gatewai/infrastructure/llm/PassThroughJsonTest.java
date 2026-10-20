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

  @Test
  void aStreamGoesUpWithItsUsageTurnedOnAndItsOtherStreamOptionsKept() {
    assertThat(PassThroughJson.forStream("{\"model\":\"auto\",\"stream\":true}", "m"))
        .isEqualTo("{\"model\":\"m\",\"stream\":true,\"stream_options\":{\"include_usage\":true}}");
    assertThat(PassThroughJson.forStream(
        "{\"stream_options\":{\"include_usage\":false,\"x\":1}}", "m"))
        .isEqualTo("{\"stream_options\":{\"include_usage\":true,\"x\":1},\"model\":\"m\","
            + "\"stream\":true}");
  }

  @Test
  void readsWhetherTheClientAskedForUsage() {
    assertThat(PassThroughJson.clientWantsUsage("{\"stream_options\":{\"include_usage\":true}}"))
        .isTrue();
    assertThat(PassThroughJson.clientWantsUsage("{\"stream\":true}")).isFalse();
  }

  @Test
  void tellsTheUsageOnlyChunkFromAChunkThatAlsoCarriesUsage() {
    assertThat(PassThroughJson.chunkUsage("{\"choices\":[],\"usage\":{\"prompt_tokens\":1,"
        + "\"completion_tokens\":2}}"))
        .isEqualTo(new PassThroughJson.ChunkUsage(new TokenUsage(1, 2, 3), true));
    // vLLM's continuous usage stats: usage on a content chunk, which must reach the client.
    assertThat(PassThroughJson.chunkUsage("{\"choices\":[{\"delta\":{}}],\"usage\":"
        + "{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}").usageOnly())
        .isFalse();
    assertThat(PassThroughJson.chunkUsage("{\"choices\":[],\"usage\":null}"))
        .isEqualTo(PassThroughJson.ChunkUsage.NONE);
    assertThat(PassThroughJson.chunkUsage("[DONE]")).isEqualTo(PassThroughJson.ChunkUsage.NONE);
  }
}
