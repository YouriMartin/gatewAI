package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptionsBuilder;

/**
 * The portable {@link ChatOptions} plus what Spring AI has no portable option
 * for: the request's {@code seed} (v4 B.1), and whether the router allows the
 * request to fail over inside its tier (v4 B.5, ADR 0017).
 *
 * <p>The advisor chain only sees portable options, and it rebuilds them twice: the
 * {@code ChatClient} merges the request's options into the egress model's own
 * ({@link DelegatingChatModel#getOptions()} returns this type, so its builder keeps
 * the seed), and the router re-targets them with {@link #mutate()}. At dispatch,
 * {@link DelegatingChatModel} hands the seed to the providers that take one, and
 * reads the failover flag; neither is ever sent to a provider.
 */
final class GatewaiChatOptions extends DefaultChatOptions {

  private final Long seed;
  private final boolean failover;

  private GatewaiChatOptions(String model, Double frequencyPenalty, Integer maxTokens,
                             Double presencePenalty, List<String> stopSequences,
                             Double temperature, Integer topK, Double topP, Long seed,
                             boolean failover) {
    super(model, frequencyPenalty, maxTokens, presencePenalty, stopSequences, temperature, topK,
        topP);
    this.seed = seed;
    this.failover = failover;
  }

  static Builder builder() {
    return new Builder();
  }

  /** The client's {@code seed}, or {@code null}. */
  Long getSeed() {
    return seed;
  }

  /** The seed of {@code options} when it carries one; {@code null} otherwise. */
  static Long seedOf(ChatOptions options) {
    return options instanceof GatewaiChatOptions gatewai ? gatewai.seed : null;
  }

  /** Whether the router allowed this request to fail over inside its tier. */
  boolean isFailover() {
    return failover;
  }

  /** The failover flag of {@code options}; false for any other options type. */
  static boolean failoverOf(ChatOptions options) {
    return options instanceof GatewaiChatOptions gatewai && gatewai.failover;
  }

  @Override
  public Builder mutate() {
    return builder()
        .model(getModel())
        .frequencyPenalty(getFrequencyPenalty())
        .maxTokens(getMaxTokens())
        .presencePenalty(getPresencePenalty())
        .stopSequences(getStopSequences())
        .temperature(getTemperature())
        .topK(getTopK())
        .topP(getTopP())
        .seed(seed)
        .failover(failover);
  }

  @Override
  public boolean equals(Object o) {
    return super.equals(o) && Objects.equals(seed, ((GatewaiChatOptions) o).seed)
        && failover == ((GatewaiChatOptions) o).failover;
  }

  @Override
  public int hashCode() {
    return 31 * (31 * super.hashCode() + Objects.hashCode(seed)) + Boolean.hashCode(failover);
  }

  /**
   * Builds {@link GatewaiChatOptions}; merging keeps the seed of the other
   * builder, and its failover flag once set.
   */
  static final class Builder extends DefaultChatOptionsBuilder<Builder> {

    private Long seed;
    private boolean failover;

    Builder seed(Long seed) {
      this.seed = seed;
      return this;
    }

    Builder failover(boolean failover) {
      this.failover = failover;
      return this;
    }

    @Override
    public Builder combineWith(ChatOptions.Builder<?> other) {
      super.combineWith(other);
      if (other instanceof Builder that) {
        if (that.seed != null) {
          this.seed = that.seed;
        }
        this.failover = this.failover || that.failover;
      }
      return this;
    }

    @Override
    public GatewaiChatOptions build() {
      return new GatewaiChatOptions(model, frequencyPenalty, maxTokens, presencePenalty,
          stopSequences, temperature, topK, topP, seed, failover);
    }
  }
}
