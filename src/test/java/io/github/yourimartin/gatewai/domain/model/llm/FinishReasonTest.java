package io.github.yourimartin.gatewai.domain.model.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FinishReasonTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      // OpenAI model (Spring AI passes the SDK enum name), Anthropic, Ollama
      "STOP, stop", "end_turn, stop", "stop_sequence, stop", "stop, stop",
      "LENGTH, length", "max_tokens, length", "length, length",
      "TOOL_CALLS, tool_calls", "tool_use, tool_calls", "FUNCTION_CALL, tool_calls",
      "CONTENT_FILTER, content_filter", "refusal, content_filter"
  })
  void providerValuesMapToTheOpenAiVocabulary(String provider, String openAi) {
    assertEquals(openAi, FinishReason.toOpenAi(provider));
  }

  @ParameterizedTest
  @ValueSource(strings = {"pause_turn", "load"})
  void anUnknownValueIsPassedThroughRatherThanGuessed(String unknown) {
    assertEquals(unknown, FinishReason.toOpenAi(unknown));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void noValueStaysNoValue(String none) {
    assertNull(FinishReason.toOpenAi(none));
  }
}
