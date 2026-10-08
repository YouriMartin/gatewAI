package io.github.yourimartin.gatewai.domain.model.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LlmRequestTest {

  @Test
  void messagesAreDefensivelyCopied() {
    List<LlmMessage> mutable = new ArrayList<>();
    mutable.add(new LlmMessage("user", "hello"));

    LlmRequest request = new LlmRequest("model", mutable, null, null);

    mutable.add(new LlmMessage("user", "injected"));

    assertEquals(1, request.messages().size(),
        "Mutating the original list must not affect the record");
  }

  @Test
  void messagesListIsUnmodifiable() {
    LlmRequest request = new LlmRequest(
        "model",
        List.of(new LlmMessage("user", "hello")),
        null,
        null
    );

    assertThrows(UnsupportedOperationException.class,
        () -> request.messages().add(new LlmMessage("user", "injected")));
  }

  @Test
  void aRequestWithoutPassThroughFeaturesIsServableByTheChain() {
    LlmRequest request = new LlmRequest("m", List.of(new LlmMessage("user", "hi")), null, null);

    request.requireServableByChain();

    assertEquals(Set.of(), request.passThrough());
    assertEquals(SamplingParameters.NONE, request.sampling());
  }

  @Test
  void aMarkedRequestIsRefusedWithItsFeaturesInDeclarationOrder() {
    LlmRequest request = new LlmRequest("m", List.of(new LlmMessage("user", "hi")), null, null,
        null, null, SamplingParameters.NONE,
        Set.of(PassThroughFeature.LOGIT_BIAS, PassThroughFeature.TOOLS));

    UnsupportedFeatureException refused =
        assertThrows(UnsupportedFeatureException.class, request::requireServableByChain);

    assertEquals(List.of(PassThroughFeature.TOOLS, PassThroughFeature.LOGIT_BIAS),
        refused.features());
    assertEquals(true, refused.getMessage().startsWith("This request uses tools, logit_bias"));
  }

  @Test
  void aMessageWithNoContentHasEmptyText() {
    LlmMessage message = new LlmMessage("assistant", null, null, null, null, null);

    assertEquals("", message.content());
    assertEquals(List.of(), message.parts());
    assertEquals(List.of(), message.toolCalls());
  }
}
