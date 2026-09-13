package io.github.yourimartin.gatewai.domain.model;

/**
 * How much the gateway actually knows about where a provider's compute runs
 * (v3 lot C.2). The region drives which grid intensity an inference is booked
 * at, so a guess and a fact must not be reported the same way.
 *
 * <p>Pure domain, zero framework dependencies.
 */
public enum RegionProvenance {

  /**
   * A fact: the operator selected the region. Bedrock, Azure OpenAI, a vLLM box,
   * any {@code openai-compatible} endpoint they host.
   */
  KNOWN("known"),

  /**
   * An operator declaration, not a fact. The direct Anthropic and OpenAI APIs do
   * not say which datacenter served a call — the number is only as good as the
   * assumption, and every export says so.
   */
  ASSUMED("assumed");

  private final String label;

  RegionProvenance(String label) {
    this.label = label;
  }

  /** Human-readable label, identical across every export format. */
  public String label() {
    return label;
  }
}
