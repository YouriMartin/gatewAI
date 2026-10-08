package io.github.yourimartin.gatewai.adapter.in.web.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import io.github.yourimartin.gatewai.domain.model.llm.ContentPart;
import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.SamplingParameters;
import io.github.yourimartin.gatewai.domain.model.llm.ToolCall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

/** The ingress reads every message shape real clients send (v4 B.1). */
class OpenAiMapperTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ChatCompletionRequest read(String json) {
    return JSON.readValue(json, ChatCompletionRequest.class);
  }

  private static ChatCompletionRequest fixture(String name) throws IOException {
    try (InputStream in = OpenAiMapperTest.class.getResourceAsStream(
        "/fixtures/openai-requests/" + name)) {
      assertThat(in).as(name).isNotNull();
      return JSON.readValue(in, ChatCompletionRequest.class);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "openai-python-string.json",
      "vercel-ai-multipart-text.json",
      "chat-ui-image.json",
      "langchain-tool-calling-turn.json",
      "developer-max-completion-tokens.json"})
  void everyFixtureDeserialisesAndMaps(String name) throws IOException {
    LlmRequest request = OpenAiMapper.toLlmRequest(fixture(name));

    assertThat(request.messages()).isNotEmpty();
  }

  @Test
  void openAiPythonShapeIsServedByTheChain() throws IOException {
    LlmRequest request = OpenAiMapper.toLlmRequest(fixture("openai-python-string.json"));

    assertThat(request.messages()).containsExactly(
        new LlmMessage("system", "Talk like a pirate."),
        new LlmMessage("user", "How do I check if a Python object is an instance of a class?"));
    assertThat(request.needsPassThrough()).isFalse();
  }

  @Test
  void multiPartTextIsFlattenedAndServedByTheChain() throws IOException {
    ChatCompletionRequest dto = fixture("vercel-ai-multipart-text.json");
    LlmRequest request = OpenAiMapper.toLlmRequest(dto);

    assertThat(request.messages().get(1)).isEqualTo(new LlmMessage("user",
        "Summarise the paragraph below in one sentence.\n"
            + "The semantic cache answers a repeated question without calling a model."));
    assertThat(request.needsPassThrough()).isFalse();
    assertThat(dto.includeUsage()).isTrue();
  }

  @Test
  void imageIsKeptAsATypedPartAndMarked() throws IOException {
    LlmRequest request = OpenAiMapper.toLlmRequest(fixture("chat-ui-image.json"));

    LlmMessage user = request.messages().getFirst();
    assertThat(user.content()).isEqualTo("What is in this picture?");
    assertThat(user.parts()).hasSize(2);
    assertThat(user.parts().get(0)).isEqualTo(new ContentPart.Text("What is in this picture?"));
    assertThat(user.parts().get(1)).isInstanceOfSatisfying(ContentPart.ImageUrl.class, image -> {
      assertThat(image.url()).startsWith("data:image/png;base64,");
      assertThat(image.detail()).isEqualTo("auto");
    });
    assertThat(request.passThrough()).containsExactly(PassThroughFeature.IMAGE_INPUT);
  }

  @Test
  void langChainToolTurnIsParsedAndMarked() throws IOException {
    LlmRequest request = OpenAiMapper.toLlmRequest(fixture("langchain-tool-calling-turn.json"));

    LlmMessage assistant = request.messages().get(1);
    assertThat(assistant.role()).isEqualTo("assistant");
    assertThat(assistant.content()).isEmpty();
    assertThat(assistant.toolCalls()).containsExactly(new ToolCall(
        "call_abc123", "function", "get_weather", "{\"city\": \"Paris\"}"));
    LlmMessage tool = request.messages().get(2);
    assertThat(tool.role()).isEqualTo("tool");
    assertThat(tool.toolCallId()).isEqualTo("call_abc123");
    assertThat(tool.content()).isEqualTo("18 degrees, light rain");
    assertThat(request.passThrough()).containsExactlyInAnyOrder(
        PassThroughFeature.TOOLS, PassThroughFeature.TOOL_MESSAGES);
  }

  @Test
  void developerRoleAndMaxCompletionTokensAreHonoured() throws IOException {
    LlmRequest request = OpenAiMapper.toLlmRequest(
        fixture("developer-max-completion-tokens.json"));

    assertThat(request.messages().getFirst().role()).isEqualTo("system");
    assertThat(request.maxTokens()).isEqualTo(512);
    assertThat(request.sampling().seed()).isEqualTo(7L);
    assertThat(request.needsPassThrough()).isFalse();
  }

  @Test
  void textPartArraysAndStringsProduceTheSameRequest() {
    LlmRequest fromString = OpenAiMapper.toLlmRequest(read("""
        {"model": "auto", "user": "u1", "stop": ["END"], "messages": [
          {"role": "system", "content": "Be brief."},
          {"role": "user", "content": "line one\\nline two"}]}
        """));
    LlmRequest fromParts = OpenAiMapper.toLlmRequest(read("""
        {"model": "auto", "user": "u1", "stop": ["END"], "messages": [
          {"role": "system", "content": [{"type": "text", "text": "Be brief."}]},
          {"role": "user", "content": [
            {"type": "text", "text": "line one"}, {"type": "text", "text": "line two"}]}]}
        """));

    assertThat(fromParts).isEqualTo(fromString);
  }

  @Test
  void forwardedParametersAreMapped() {
    LlmRequest request = OpenAiMapper.toLlmRequest(read("""
        {"model": "auto", "messages": [{"role": "user", "content": "hi"}],
         "top_p": 0.9, "presence_penalty": 0.1, "frequency_penalty": 0.2, "seed": 9,
         "max_tokens": 50, "stop": "END"}
        """));

    assertThat(request.sampling()).isEqualTo(new SamplingParameters(0.9, 0.1, 0.2, 9L));
    assertThat(request.maxTokens()).isEqualTo(50);
    assertThat(request.stop()).containsExactly("END");
  }

  @Test
  void maxCompletionTokensWinsOverMaxTokens() {
    LlmRequest request = OpenAiMapper.toLlmRequest(read("""
        {"model": "auto", "messages": [{"role": "user", "content": "hi"}],
         "max_tokens": 50, "max_completion_tokens": 80}
        """));

    assertThat(request.maxTokens()).isEqualTo(80);
  }

  @Test
  void unknownFieldsAreIgnored() {
    LlmRequest request = OpenAiMapper.toLlmRequest(read("""
        {"model": "auto", "messages": [{"role": "user", "content": "hi", "extra": 1}],
         "metadata": {"k": "v"}, "service_tier": "auto", "parallel_tool_calls": true}
        """));

    assertThat(request.needsPassThrough()).isFalse();
  }

  @Test
  void eachPassThroughFieldIsMarked() {
    assertThat(features("\"response_format\": {\"type\": \"json_object\"}"))
        .containsExactly(PassThroughFeature.RESPONSE_FORMAT);
    assertThat(features("\"n\": 2")).containsExactly(PassThroughFeature.MULTIPLE_CHOICES);
    assertThat(features("\"logprobs\": true")).containsExactly(PassThroughFeature.LOGPROBS);
    assertThat(features("\"top_logprobs\": 3")).containsExactly(PassThroughFeature.LOGPROBS);
    assertThat(features("\"logit_bias\": {\"50256\": -100}"))
        .containsExactly(PassThroughFeature.LOGIT_BIAS);
    assertThat(features("\"functions\": [{\"name\": \"f\"}]"))
        .containsExactly(PassThroughFeature.TOOLS);
  }

  @Test
  void defaultValuesOfPassThroughFieldsAreNotMarked() {
    assertThat(features("\"response_format\": {\"type\": \"text\"}, \"n\": 1,"
        + " \"logprobs\": false, \"logit_bias\": {}, \"tools\": []")).isEmpty();
  }

  @Test
  void nonTextPartsAreMarkedByType() {
    assertThat(partFeatures("{\"type\": \"input_audio\","
        + " \"input_audio\": {\"data\": \"AAAA\", \"format\": \"wav\"}}"))
        .containsExactly(PassThroughFeature.AUDIO_INPUT);
    assertThat(partFeatures("{\"type\": \"file\", \"file\": {\"file_id\": \"file-1\"}}"))
        .containsExactly(PassThroughFeature.FILE_INPUT);
    assertThat(partFeatures("{\"type\": \"video_url\", \"video_url\": {\"url\": \"x\"}}"))
        .containsExactly(PassThroughFeature.OTHER_CONTENT);
  }

  @Test
  void unknownPartsArePreservedWithTheirJson() {
    LlmRequest request = OpenAiMapper.toLlmRequest(read("""
        {"messages": [{"role": "user", "content": [
          {"type": "video_url", "video_url": {"url": "https://example.com/v.mp4"}}]}]}
        """));

    assertThat(request.messages().getFirst().parts().getFirst())
        .isInstanceOfSatisfying(ContentPart.Other.class, other -> {
          assertThat(other.type()).isEqualTo("video_url");
          assertThat(other.json()).contains("https://example.com/v.mp4");
        });
  }

  @Test
  void legacyFunctionCallIsPreservedAsACallWithNoId() {
    LlmRequest request = OpenAiMapper.toLlmRequest(read("""
        {"messages": [
          {"role": "user", "content": "weather?"},
          {"role": "assistant", "content": null,
           "function_call": {"name": "get_weather", "arguments": "{}"}},
          {"role": "function", "name": "get_weather", "content": "sunny"}]}
        """));

    assertThat(request.messages().get(1).toolCalls())
        .containsExactly(new ToolCall(null, "function", "get_weather", "{}"));
    assertThat(request.messages().get(2).name()).isEqualTo("get_weather");
    assertThat(request.passThrough()).containsExactly(PassThroughFeature.TOOL_MESSAGES);
  }

  @Test
  void anUnknownRoleIsRefused() {
    assertThatThrownBy(() -> OpenAiMapper.toLlmRequest(read("""
        {"messages": [{"role": "robot", "content": "hi"}]}
        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("robot");
  }

  @Test
  void contentOfAnotherTypeIsRefused() {
    assertThatThrownBy(() -> OpenAiMapper.toLlmRequest(read("""
        {"messages": [{"role": "user", "content": 42}]}
        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("messages[0].content");
  }

  private static Set<PassThroughFeature> features(String fields) {
    return OpenAiMapper.toLlmRequest(read("{\"messages\": [{\"role\": \"user\","
        + " \"content\": \"hi\"}], " + fields + "}")).passThrough();
  }

  private static Set<PassThroughFeature> partFeatures(String part) {
    return OpenAiMapper.toLlmRequest(read("{\"messages\": [{\"role\": \"user\","
        + " \"content\": [" + part + "]}]}")).passThrough();
  }
}
