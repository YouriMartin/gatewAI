package io.github.yourimartin.gatewai.domain.model.client;

/**
 * What the gateway does with a client's requests (v4 D.1).
 *
 * <p>Also stamped on every cache and routing decision, with the mode of the client
 * whose request it was taken for: a {@code SHADOW} row is what the gateway
 * <b>would</b> have done, never what the client received.
 */
public enum ClientMode {

  /** Today's behaviour: the cache answers and the router picks the model. */
  ENFORCE,

  /**
   * The client receives the upstream answer of the model it named, unchanged. The
   * cache and the router decide off the request path, and their decisions are
   * recorded, not applied.
   */
  SHADOW
}
