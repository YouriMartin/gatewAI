package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
