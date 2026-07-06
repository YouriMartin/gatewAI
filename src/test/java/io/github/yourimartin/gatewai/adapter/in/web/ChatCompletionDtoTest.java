package io.github.yourimartin.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ChatCompletionDtoTest {

  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void requestDeserializesFromSnakeCaseJson() throws Exception {
    String json = """
        {
          "model": "gpt-4",
          "messages": [
            {"role": "system", "content": "You are helpful."},
            {"role": "user", "content": "Hello"}
          ],
          "temperature": 0.7,
          "max_tokens": 256,
          "top_p": 0.9,
          "stream": false,
          "n": 1,
          "stop": ["\\n"],
          "presence_penalty": 0.5,
          "frequency_penalty": 0.3,
          "user": "user-42"
        }
        """;

    ChatCompletionRequest req = mapper.readValue(json, ChatCompletionRequest.class);

    assertEquals("gpt-4", req.model());
    assertEquals(2, req.messages().size());
    assertEquals("system", req.messages().get(0).role());
    assertEquals("You are helpful.", req.messages().get(0).content());
    assertEquals("user", req.messages().get(1).role());
    assertEquals("Hello", req.messages().get(1).content());
    assertEquals(0.7, req.temperature());
    assertEquals(256, req.maxTokens());
    assertEquals(0.9, req.topP());
    assertEquals(false, req.stream());
    assertEquals(1, req.n());
    assertEquals(List.of("\n"), req.stop());
    assertEquals(0.5, req.presencePenalty());
    assertEquals(0.3, req.frequencyPenalty());
    assertEquals("user-42", req.user());
  }

  @Test
  void requestDeserializesWithOptionalFieldsMissing() throws Exception {
    String json = """
        {
          "model": "gpt-4",
          "messages": [{"role": "user", "content": "Hi"}]
        }
        """;

    ChatCompletionRequest req = mapper.readValue(json, ChatCompletionRequest.class);

    assertEquals("gpt-4", req.model());
    assertEquals(1, req.messages().size());
    assertNull(req.temperature());
    assertNull(req.maxTokens());
    assertNull(req.topP());
    assertNull(req.stream());
    assertNull(req.n());
    assertNull(req.stop());
    assertNull(req.presencePenalty());
    assertNull(req.frequencyPenalty());
    assertNull(req.user());
  }

  @Test
  void responseSerializesToSnakeCaseJson() throws Exception {
    ChatCompletionResponse response = new ChatCompletionResponse(
        "chatcmpl-abc123",
        "chat.completion",
        1700000000L,
        "gpt-4",
        List.of(new ChatChoice(
            0,
            new ChatMessage("assistant", "Hello!"),
            "stop"
        )),
        new TokenUsage(10, 5, 15),
        "fp_abc123"
    );

    String json = mapper.writeValueAsString(response);

    // Verify snake_case keys are present
    assertEquals(true, json.contains("\"finish_reason\""));
    assertEquals(true, json.contains("\"system_fingerprint\""));
    assertEquals(true, json.contains("\"prompt_tokens\""));
    assertEquals(true, json.contains("\"completion_tokens\""));
    assertEquals(true, json.contains("\"total_tokens\""));

    // Verify camelCase keys are NOT present
    assertEquals(false, json.contains("\"finishReason\""));
    assertEquals(false, json.contains("\"systemFingerprint\""));
    assertEquals(false, json.contains("\"promptTokens\""));
    assertEquals(false, json.contains("\"completionTokens\""));
    assertEquals(false, json.contains("\"totalTokens\""));

    // Round-trip: deserialize back and verify
    ChatCompletionResponse roundTrip = mapper.readValue(json, ChatCompletionResponse.class);
    assertEquals("chatcmpl-abc123", roundTrip.id());
    assertEquals("chat.completion", roundTrip.object());
    assertEquals(1700000000L, roundTrip.created());
    assertEquals("gpt-4", roundTrip.model());
    assertEquals(1, roundTrip.choices().size());
    assertEquals("stop", roundTrip.choices().get(0).finishReason());
    assertEquals("assistant", roundTrip.choices().get(0).message().role());
    assertEquals("Hello!", roundTrip.choices().get(0).message().content());
    assertEquals(10, roundTrip.usage().promptTokens());
    assertEquals(5, roundTrip.usage().completionTokens());
    assertEquals(15, roundTrip.usage().totalTokens());
    assertEquals("fp_abc123", roundTrip.systemFingerprint());
  }
}
