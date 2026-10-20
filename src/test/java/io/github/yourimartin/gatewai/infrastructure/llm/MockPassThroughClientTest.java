package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MockPassThroughClientTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ModelDefinition TARGET =
      new ModelDefinition("k", "ollama", "qwen2.5:3b", 0.0, null, ModelTier.CLOUD_PREMIUM);

  private final MockPassThroughClient client = new MockPassThroughClient();

  @Test
  void aRequestWithToolsGetsAScriptedCallToTheFirstTool() {
    JsonNode answer = forward("""
        {"model": "auto", "messages": [{"role": "user", "content": "Weather in Paris?"}],
         "tools": [{"type": "function", "function": {"name": "get_weather"}},
                   {"type": "function", "function": {"name": "get_time"}}]}
        """);

    JsonNode choice = answer.path("choices").get(0);
    assertThat(choice.path("finish_reason").asString()).isEqualTo("tool_calls");
    JsonNode call = choice.path("message").path("tool_calls").get(0);
    assertThat(call.path("id").asString()).isEqualTo(MockPassThroughClient.TOOL_CALL_ID);
    assertThat(call.path("function").path("name").asString()).isEqualTo("get_weather");
    assertThat(call.path("function").path("arguments").asString()).isEqualTo("{}");
    assertThat(answer.path("model").asString()).isEqualTo("qwen2.5:3b");
  }

  @Test
  void aToolResultGetsAFinalAnswerThatQuotesIt() {
    JsonNode answer = forward("""
        {"messages": [{"role": "user", "content": "Weather?"},
          {"role": "assistant", "content": null, "tool_calls": [{"id": "call_mock_1",
            "type": "function", "function": {"name": "get_weather", "arguments": "{}"}}]},
          {"role": "tool", "tool_call_id": "call_mock_1", "content": "18 degrees"}],
         "tools": [{"type": "function", "function": {"name": "get_weather"}}]}
        """);

    assertThat(answer.path("choices").get(0).path("finish_reason").asString()).isEqualTo("stop");
    assertThat(answer.path("choices").get(0).path("message").path("content").asString())
        .isEqualTo("[mock] tool result: 18 degrees");
  }

  @Test
  void aJsonResponseFormatGetsValidJsonOncePerChoice() {
    JsonNode answer = forward("""
        {"n": 2, "response_format": {"type": "json_object"},
         "messages": [{"role": "user", "content": [{"type": "text", "text": "a colour"},
           {"type": "image_url", "image_url": {"url": "https://example.com/a.png"}}]}]}
        """);

    assertThat(answer.path("choices")).hasSize(2);
    String content = answer.path("choices").get(1).path("message").path("content").asString();
    assertThat(JSON.readTree(content).path("echo").asString()).isEqualTo("[mock] echo: a colour");
  }

  @Test
  void theUsageIsReportedAndAccountedTheSameWay() {
    PassThroughResponse response = client.forward(TARGET,
        "{\"messages\":[{\"role\":\"user\",\"content\":\"one two three\"}],\"n\":2}");

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.model()).isEqualTo("qwen2.5:3b");
    assertThat(response.usage().promptTokens()).isEqualTo(3);
    assertThat(response.usage().totalTokens())
        .isEqualTo(JSON.readTree(response.body()).path("usage").path("total_tokens").asInt());
    assertThat(client.ignoredFeatures(TARGET)).isEmpty();
  }

  private JsonNode forward(String body) {
    return JSON.readTree(client.forward(TARGET, body).body());
  }
}
