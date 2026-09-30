package io.github.yourimartin.gatewai.eval;

import java.util.List;

/**
 * One labelled conversation case (v4 batch A.1): may the answer cached for
 * {@code stored} be served to {@code incoming}?
 *
 * <p>Both sides are whole OpenAI {@code messages} arrays, because the question
 * this set asks is precisely what the cache does with everything <i>around</i>
 * the last user turn — the system prompt and the history.
 *
 * @param servable the human judgment: true when the stored answer would be a
 *                 correct answer to {@code incoming}, in its context
 */
record ConversationCase(String id, List<Turn> stored, List<Turn> incoming, boolean servable,
                        String language, List<String> tags) {

  ConversationCase {
    stored = List.copyOf(stored);
    incoming = List.copyOf(incoming);
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  /** The text of the last user turn of a side — what a long-prompt check looks at. */
  static String lastUserText(List<Turn> turns) {
    for (int i = turns.size() - 1; i >= 0; i--) {
      if ("user".equals(turns.get(i).role())) {
        return turns.get(i).content();
      }
    }
    throw new IllegalStateException("A conversation case has no user turn");
  }

  /** One OpenAI chat message, as a client sends it. */
  record Turn(String role, String content) {
  }
}
