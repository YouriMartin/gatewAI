package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MockPassThroughClientTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ModelDefinition TARGET =
      new ModelDefinition("k", "ollama", "qwen2.5:3b", ModelPricing.NONE, null, ModelTier.CLOUD_PREMIUM);

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

  @Test
  void streamsTheScriptedToolCallTheOpenAiWay() {
    List<String> events = new ArrayList<>();

    TokenUsage usage = client.openStream(TARGET, """
        {"stream": true, "messages": [{"role": "user", "content": "Weather?"}],
         "tools": [{"type": "function", "function": {"name": "get_weather"}}]}
        """).relay(events::add);

    assertThat(events.getFirst()).contains("\"role\":\"assistant\"");
    JsonNode call = JSON.readTree(events.get(1)).path("choices").get(0).path("delta")
        .path("tool_calls").get(0);
    assertThat(call.path("index").asInt()).isZero();
    assertThat(call.path("function").path("name").asString()).isEqualTo("get_weather");
    assertThat(events.get(2)).contains("\"finish_reason\":\"tool_calls\"");
    // The usage chunk is read but kept from a client that did not ask for it.
    assertThat(events.getLast()).isEqualTo("[DONE]");
    assertThat(events).noneMatch(event -> event.contains("\"choices\":[]"));
    assertThat(usage).isNotNull();
  }

  @Test
  void streamsTextWordByWordAndTheUsageChunkWhenAskedFor() {
    List<String> events = new ArrayList<>();

    client.openStream(TARGET, """
        {"stream": true, "stream_options": {"include_usage": true},
         "messages": [{"role": "user", "content": "one two"}]}
        """).relay(events::add);

    StringBuilder text = new StringBuilder();
    events.stream().filter(event -> event.contains("\"content\""))
        .forEach(event -> text.append(JSON.readTree(event).path("choices").get(0)
            .path("delta").path("content").asString()));
    assertThat(text).hasToString("[mock] echo: one two");
    assertThat(events.get(events.size() - 2)).contains("\"choices\":[]").contains("\"usage\"");
  }
}
