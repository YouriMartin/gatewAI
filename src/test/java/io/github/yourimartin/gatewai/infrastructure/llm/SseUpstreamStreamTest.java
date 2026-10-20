package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.llm.ClientDisconnectedException;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;

import org.junit.jupiter.api.Test;

class SseUpstreamStreamTest {

  private static final String CHUNK = "{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}],\"usage\":null}";
  private static final String USAGE =
      "{\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}";

  @Test
  void relaysEachDataPayloadInOrderAndReturnsTheUsage() {
    List<String> events = new ArrayList<>();

    TokenUsage usage = stream("data: " + CHUNK + "\n\ndata: " + USAGE + "\n\ndata: [DONE]\n\n",
        false).relay(events::add);

    assertThat(events).containsExactly(CHUNK, "[DONE]");
    assertThat(usage).isEqualTo(new TokenUsage(3, 1, 4));
  }

  @Test
  void keepsTheUsageChunkWhenTheClientAskedForIt() {
    List<String> events = new ArrayList<>();

    stream("data: " + USAGE + "\n\ndata: [DONE]\n\n", true).relay(events::add);

    assertThat(events).containsExactly(USAGE, "[DONE]");
  }

  @Test
  void ignoresCommentsAndOtherFieldsAndJoinsMultiLineData() {
    List<String> events = new ArrayList<>();

    stream(": keep-alive\n\nevent: message\nid: 7\ndata:{\"a\":\ndata: 1}\n\ndata: [DONE]", false)
        .relay(events::add);

    // The last event had no closing blank line: it was still sent.
    assertThat(events).containsExactly("{\"a\":\n1}", "[DONE]");
  }

  @Test
  void aStreamWithoutUsageReturnsNull() {
    assertThat(stream("data: " + CHUNK + "\n\n", false).relay(event -> { })).isNull();
  }

  @Test
  void aClientThatGoesAwayClosesTheUpstream() {
    TrackedInput input = new TrackedInput("data: " + CHUNK + "\n\ndata: " + CHUNK + "\n\n");
    SseUpstreamStream stream = new SseUpstreamStream(input, false, "m");

    assertThatThrownBy(() -> stream.relay(event -> {
      throw new ClientDisconnectedException("gone", null);
    })).isInstanceOf(ClientDisconnectedException.class);
    assertThat(input.closed).isTrue();
  }

  @Test
  void aReadFailureIsAnUpstreamFailureUnlessTheStreamWasCancelled() {
    InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("connection reset");
      }
    };

    assertThatThrownBy(() -> new SseUpstreamStream(broken, false, "m").relay(event -> { }))
        .isInstanceOfSatisfying(UpstreamFailureException.class,
            e -> assertThat(e.kind()).isEqualTo(UpstreamFailureException.Kind.UNAVAILABLE));

    SseUpstreamStream cancelled = new SseUpstreamStream(broken, false, "m");
    cancelled.cancel();
    assertThatThrownBy(() -> cancelled.relay(event -> { }))
        .isInstanceOf(ClientDisconnectedException.class);
  }

  private static SseUpstreamStream stream(String sse, boolean clientWantsUsage) {
    return new SseUpstreamStream(
        new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), clientWantsUsage, "m");
  }

  private static final class TrackedInput extends ByteArrayInputStream {

    private boolean closed;

    TrackedInput(String text) {
      super(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
