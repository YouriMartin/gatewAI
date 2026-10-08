package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptionsBuilder;

/**
 * The portable {@link ChatOptions} plus the request parameters Spring AI has no
 * portable option for — today only {@code seed} (v4 B.1).
 *
 * <p>The advisor chain only sees portable options, and it rebuilds them twice: the
 * {@code ChatClient} merges the request's options into the egress model's own
 * ({@link DelegatingChatModel#getOptions()} returns this type, so its builder keeps
 * the seed), and the router re-targets them with {@link #mutate()}. At dispatch,
 * {@link DelegatingChatModel} hands the seed to the providers that take one.
 */
final class GatewaiChatOptions extends DefaultChatOptions {

  private final Long seed;

  private GatewaiChatOptions(String model, Double frequencyPenalty, Integer maxTokens,
                             Double presencePenalty, List<String> stopSequences,
                             Double temperature, Integer topK, Double topP, Long seed) {
    super(model, frequencyPenalty, maxTokens, presencePenalty, stopSequences, temperature, topK,
        topP);
    this.seed = seed;
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
        .seed(seed);
  }

  @Override
  public boolean equals(Object o) {
    return super.equals(o) && Objects.equals(seed, ((GatewaiChatOptions) o).seed);
  }

  @Override
  public int hashCode() {
    return 31 * super.hashCode() + Objects.hashCode(seed);
  }

  /** Builds {@link GatewaiChatOptions}; merging keeps the seed of the other builder. */
  static final class Builder extends DefaultChatOptionsBuilder<Builder> {

    private Long seed;

    Builder seed(Long seed) {
      this.seed = seed;
      return this;
    }

    @Override
    public Builder combineWith(ChatOptions.Builder<?> other) {
      super.combineWith(other);
      if (other instanceof Builder that && that.seed != null) {
        this.seed = that.seed;
      }
      return this;
    }

    @Override
    public GatewaiChatOptions build() {
      return new GatewaiChatOptions(model, frequencyPenalty, maxTokens, presencePenalty,
          stopSequences, temperature, topK, topP, seed);
    }
  }
}
