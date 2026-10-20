package io.github.yourimartin.gatewai.domain.port.out;

import java.util.function.Consumer;

import io.github.yourimartin.gatewai.domain.model.llm.ClientDisconnectedException;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;

/**
 * An upstream Server-Sent Events stream that has answered 2xx and not been read
 * yet (v4 B.4). The events are opaque to the domain: each is the {@code data}
 * payload of one event, a JSON chunk or {@code [DONE]}.
 */
public interface UpstreamEventStream {

  /**
   * Hands each event to {@code onEvent} as soon as it arrives — never buffered to
   * the end — until the upstream closes the stream. The usage-only chunk is kept
   * from {@code onEvent} unless the client asked for it.
   *
   * @return the usage the upstream reported, or {@code null} when it reported none
   * @throws ClientDisconnectedException when {@code onEvent} throws it, or after
   *                                     {@link #cancel()}; the upstream is closed
   * @throws UpstreamFailureException    when the upstream connection fails mid-stream
   */
  TokenUsage relay(Consumer<String> onEvent);

  /** Closes the upstream connection. Safe to call from another thread, and twice. */
  void cancel();
}
