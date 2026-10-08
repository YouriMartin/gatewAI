package io.github.yourimartin.gatewai.domain.model.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.decision.FieldDigest;

/**
 * How a conversation began, and the fingerprint that recognises it on a later
 * turn (ADR 0015, v4 A.3).
 *
 * <p>Chat Completions is stateless, but clients resend the history, so the
 * opening of a conversation is the part that stays the same from one turn to the
 * next: every system message before the first user message, the first user
 * message, and the first assistant message. A system message a client adds
 * later in the conversation does not change it.
 *
 * <p>The first answer is part of it on purpose: two conversations that open
 * with the same question usually diverge on the answer, and the answer is what
 * the gateway itself produced, so it is the part a client is least likely to
 * have invented. Two conversations with an identical opening still share one
 * fingerprint — that is a documented limitation, not a collision.
 *
 * <p>Only ever stored as a hash. Changing the encoding means bumping
 * {@link #VERSION}, which makes every stored record unreachable: conversations
 * then fall back to their first-turn floor, the safe direction.
 */
public final class ConversationOpening {

  static final String VERSION = "gatewai-conversation/v1";

  static final String SYSTEM = "system";
  static final String USER = "user";
  static final String ASSISTANT = "assistant";

  private final List<String> system;
  private final String firstUser;
  private final String firstAnswer;

  private ConversationOpening(List<String> system, String firstUser,
                              String firstAnswer) {
    this.system = List.copyOf(system);
    this.firstUser = firstUser;
    this.firstAnswer = firstAnswer;
  }

  /** True when the request already holds an answer, i.e. it is not a first turn. */
  public static boolean hasHistory(List<Turn> messages) {
    return messages.stream().anyMatch(turn -> ASSISTANT.equals(turn.role()));
  }

  /**
   * The opening of a request that carries history.
   *
   * @return empty when the request has no assistant message or no user message
   */
  public static Optional<ConversationOpening> ofHistory(List<Turn> messages) {
    String firstAnswer = messages.stream()
        .filter(turn -> ASSISTANT.equals(turn.role()))
        .map(Turn::text)
        .findFirst().orElse(null);
    if (firstAnswer == null) {
      return Optional.empty();
    }
    return opening(messages, firstAnswer);
  }

  /**
   * The opening a first turn has once {@code answer} is sent back — the one its
   * second turn will carry.
   *
   * @return empty when the request already has history, has no user message,
   *         or the answer is empty
   */
  public static Optional<ConversationOpening> ofFirstTurn(List<Turn> messages,
                                                          String answer) {
    if (answer == null || answer.isEmpty() || hasHistory(messages)) {
      return Optional.empty();
    }
    return opening(messages, answer);
  }

  private static Optional<ConversationOpening> opening(List<Turn> messages,
                                                       String answer) {
    List<String> system = new ArrayList<>();
    for (Turn turn : messages) {
      if (USER.equals(turn.role())) {
        return Optional.of(new ConversationOpening(system, turn.text(), answer));
      }
      if (SYSTEM.equals(turn.role())) {
        system.add(turn.text());
      }
    }
    return Optional.empty();
  }

  /** The first user message — what the conversation's first turn was classified on. */
  public String firstUserText() {
    return firstUser;
  }

  /** The 64-character hex SHA-256 of the opening. */
  public String fingerprint() {
    FieldDigest digest = FieldDigest.begin(VERSION).count(system.size());
    system.forEach(digest::field);
    return digest.field(firstUser).field(firstAnswer).hex();
  }

  /**
   * One message of the request.
   *
   * @param role the message's role as the chain sees it ({@code system},
   *             {@code user}, {@code assistant}, {@code tool})
   * @param text its text, or null when it has none
   */
  public record Turn(String role, String text) {
  }
}
