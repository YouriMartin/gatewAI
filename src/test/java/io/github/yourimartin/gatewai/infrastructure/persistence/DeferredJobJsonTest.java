package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import io.github.yourimartin.gatewai.domain.model.llm.SamplingParameters;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;

import org.junit.jupiter.api.Test;

class DeferredJobJsonTest {

  @Test
  void aRequestRoundTripsWithItsMessageOrder() {
    LlmRequest request = new LlmRequest("claude", List.of(
        new LlmMessage("system", "Tu es un assistant."),
        new LlmMessage("user", "Résume ce texte en trois phrases")), 0.3, 512);

    assertEquals(request, DeferredJobJson.requestFromJson(
        DeferredJobJson.requestToJson(request)));
  }

  @Test
  void optionalRequestFieldsSurviveAsNullRatherThanZero() {
    // A job stored with no temperature must not come back asking for 0.0 —
    // that is a different request from the one the client submitted.
    LlmRequest request = new LlmRequest(
        "qwen", List.of(new LlmMessage("user", "hi")), null, null);

    LlmRequest parsed = DeferredJobJson.requestFromJson(
        DeferredJobJson.requestToJson(request));

    assertNull(parsed.temperature());
    assertNull(parsed.maxTokens());
    assertEquals(request, parsed);
  }

  @Test
  void stopAndTheEndUserSurvive() {
    // Both are part of the cache scope (v4 A.2): a deferred job that lost its
    // end user would share answers with every other end user of the key.
    LlmRequest request = new LlmRequest("qwen", List.of(new LlmMessage("user", "hi")),
        null, null, List.of("END", "\n\n"), "end-user-7");

    assertEquals(request, DeferredJobJson.requestFromJson(
        DeferredJobJson.requestToJson(request)));
  }

  @Test
  void aJobStoredBeforeV4ReadsBackWithoutStopOrEndUser() {
    LlmRequest parsed = DeferredJobJson.requestFromJson(
        "{\"model\":\"qwen\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
            + "\"temperature\":null,\"maxTokens\":null}");

    assertNull(parsed.stop());
    assertNull(parsed.user());
  }

  @Test
  void aRequestWithNoMessagesReadsBackAsEmpty() {
    LlmRequest parsed = DeferredJobJson.requestFromJson(
        DeferredJobJson.requestToJson(
            new LlmRequest("qwen", null, null, null)));

    assertTrue(parsed.messages().isEmpty());
  }

  @Test
  void aResponseRoundTripsIncludingTheCacheHitFlag() {
    LlmResponse response = new LlmResponse(
        "qwen2.5:7b", "Voici le résumé.", "stop", 12, 34, 46, true);

    assertEquals(response, DeferredJobJson.responseFromJson(
        DeferredJobJson.responseToJson(response)));
  }

  @Test
  void anAbsentResultStaysAbsent() {
    assertNull(DeferredJobJson.responseToJson(null));
    assertNull(DeferredJobJson.responseFromJson(null));
    assertNull(DeferredJobJson.responseFromJson(""));
  }

  @Test
  void theSamplingParametersSurvive() {
    LlmRequest request = new LlmRequest("auto", List.of(new LlmMessage("user", "hi")),
        null, null, null, null, new SamplingParameters(0.9, 0.1, 0.2, 1L << 40), Set.of());

    LlmRequest back = DeferredJobJson.requestFromJson(DeferredJobJson.requestToJson(request));

    assertEquals(request.sampling(), back.sampling());
  }
}
