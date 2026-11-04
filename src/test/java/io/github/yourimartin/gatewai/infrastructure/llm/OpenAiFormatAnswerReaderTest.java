package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.port.out.RelayedAnswerReader;

import org.junit.jupiter.api.Test;

class OpenAiFormatAnswerReaderTest {

  private static final TokenUsage USAGE = new TokenUsage(3, 2, 5);

  private final OpenAiFormatAnswerReader reader = new OpenAiFormatAnswerReader();

  @Test
  void readsTheTextAndFinishReasonOfACall() {
    RelayedAnswer answer = reader.read(response("""
        {"choices":[{"index":0,"message":{"role":"assistant","content":"Hello."},
          "finish_reason":"stop"}]}""")).orElseThrow();

    assertThat(answer).isEqualTo(new RelayedAnswer("m1", "Hello.", "stop", USAGE));
  }

  @Test
  void anAnthropicStyleFinishReasonIsMappedToOpenAis() {
    assertThat(reader.read(response("""
        {"choices":[{"message":{"content":"Hi"},"finish_reason":"end_turn"}]}"""))
        .orElseThrow().finishReason()).isEqualTo("stop");
  }

  @Test
  void aBodyWithoutASingleTextAnswerHasNone() {
    assertThat(reader.read(response("not json"))).isEmpty();
    assertThat(reader.read(response("{\"choices\":[]}"))).isEmpty();
    assertThat(reader.read(response("""
        {"choices":[{"message":{"content":null,"tool_calls":[]},
          "finish_reason":"tool_calls"}]}"""))).isEmpty();
  }

  @Test
  void concatenatesTheDeltasOfAStream() {
    RelayedAnswerReader.StreamedAnswer streamed = reader.streamed("m1");
    streamed.accept("{\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}");
    streamed.accept("{\"choices\":[{\"delta\":{\"content\":\"It is \"},\"finish_reason\":null}]}");
    streamed.accept("{\"choices\":[{\"delta\":{\"content\":\"18.\"},\"finish_reason\":null}]}");
    streamed.accept("{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}");
    streamed.accept("{\"choices\":[],\"usage\":{\"total_tokens\":5}}");
    streamed.accept("[DONE]");

    assertThat(streamed.answer(USAGE)).contains(
        new RelayedAnswer("m1", "It is 18.", "length", USAGE));
  }

  @Test
  void aStreamWithNoChoiceHasNoAnswer() {
    RelayedAnswerReader.StreamedAnswer streamed = reader.streamed("m1");
    streamed.accept("[DONE]");

    assertThat(streamed.answer(null)).isEmpty();
  }

  private static PassThroughResponse response(String body) {
    return new PassThroughResponse(200, body, "m1", USAGE);
  }
}
