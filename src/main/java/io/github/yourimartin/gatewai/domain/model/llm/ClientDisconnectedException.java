package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * The client of a streamed pass-through went away before the end of the stream
 * (v4 B.4). Thrown by whoever writes to the client; ends the relay and cancels
 * the upstream call.
 */
public class ClientDisconnectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ClientDisconnectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
