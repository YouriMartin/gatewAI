package io.github.yourimartin.gatewai.domain.model.cache;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.decision.FieldDigest;

/**
 * The conversation context a cached answer is valid in (ADR 0014, v4 A.2).
 *
 * <p>The semantic cache compares the <b>last user turn</b> by similarity, and
 * only inside one scope: everything else that decides what a correct answer is
 * — the system prompt, the history, the pinned model, the end user, the stop
 * sequences — has to be identical, byte for byte. This is that identity, as a
 * SHA-256.
 *
 * <p>The encoding is a version tag followed by length-prefixed UTF-8 fields, so
 * no text can forge a boundary between two fields, and it applies no
 * normalisation: any difference is a different context, which only ever errs
 * towards a refusal. Changing the encoding means bumping {@link #VERSION}, which
 * makes every stored entry unreachable — the safe direction.
 */
public final class CacheScope {

  static final String VERSION = "gatewai-cache-scope/v1";

  private CacheScope() {
  }

  /**
   * The scope hash.
   *
   * @param context     every message of the request except the last user turn,
   *                    in order
   * @param pinnedModel the requested model when it is a registered id, else null
   * @param endUser     the OpenAI {@code user} field, or null
   * @param stop        the stop sequences, or null
   * @return a 64-character hex SHA-256
   */
  public static String of(List<Turn> context, String pinnedModel, String endUser,
                          List<String> stop) {
    FieldDigest digest = FieldDigest.begin(VERSION).count(context.size());
    for (Turn turn : context) {
      digest.field(turn.role()).field(turn.text());
    }
    digest.field(pinnedModel).field(endUser);
    if (stop == null) {
      digest.absent();
    } else {
      digest.count(stop.size());
      stop.forEach(digest::field);
    }
    return digest.hex();
  }

  /**
   * One message of the context.
   *
   * @param role the message's role as the chain sees it ({@code system},
   *             {@code user}, {@code assistant}, {@code tool})
   * @param text its text, or null when it has none
   */
  public record Turn(String role, String text) {
  }
}
