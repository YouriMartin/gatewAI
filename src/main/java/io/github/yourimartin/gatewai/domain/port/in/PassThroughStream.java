package io.github.yourimartin.gatewai.domain.port.in;

import java.util.function.Consumer;

import io.github.yourimartin.gatewai.domain.model.llm.ClientDisconnectedException;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;

/**
 * A streamed pass-through whose upstream has answered 2xx (v4 B.4). Opening it is
 * synchronous, so an upstream that refuses the request is an HTTP error for the
 * client, not an error inside a stream it already received.
 */
public interface PassThroughStream {

  /** The registry model id the request was forwarded to. */
  String model();

  /**
   * Relays the stream to {@code onEvent}, then accounts the request. Blocks until
   * the upstream ends, the client goes away or the upstream fails.
   *
   * @throws ClientDisconnectedException when the client went away; recorded, not
   *                                     accounted
   * @throws UpstreamFailureException    when the upstream failed mid-stream
   */
  void relay(Consumer<String> onEvent);

  /** The client is gone: cancels the upstream call. */
  void cancel();
}
