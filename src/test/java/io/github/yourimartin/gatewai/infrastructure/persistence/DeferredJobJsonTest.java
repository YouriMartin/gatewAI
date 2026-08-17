package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;

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
}
