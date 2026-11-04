package io.github.yourimartin.gatewai.domain.port.out;

import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;

/**
 * Reads the assistant answer out of what the pass-through path relayed (v4 D.1).
 * The bodies and events stay opaque to the domain: this is the one place that
 * knows where an OpenAI-format answer keeps its text.
 */
public interface RelayedAnswerReader {

  /**
   * The answer of a non-streamed call; empty when the body holds no single text
   * answer — not JSON, no choice, or a content that is not a string.
   */
  Optional<RelayedAnswer> read(PassThroughResponse response);

  /** A reader of a stream's events, fed as they are relayed. */
  StreamedAnswer streamed(String model);

  /** Accumulates the text deltas of one relayed stream. Not thread-safe. */
  interface StreamedAnswer {

    /** One event's {@code data}, as relayed. */
    void accept(String event);

    /**
     * The answer once the stream has ended; empty when no event carried a choice.
     *
     * @param usage the usage the upstream reported, null when none
     */
    Optional<RelayedAnswer> answer(TokenUsage usage);
  }
}
