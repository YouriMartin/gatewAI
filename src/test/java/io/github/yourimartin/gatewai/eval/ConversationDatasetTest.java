package io.github.yourimartin.gatewai.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Checks the conversation dataset itself, before anything is scored on it
 * (v4 batch A.1).
 *
 * <p>The labels are the harness's ground truth, so a mislabelled case does not
 * fail anything downstream — it just moves a rate. These checks turn the
 * labelling rules of {@code src/test/resources/eval/README.md} into assertions,
 * so an edit that breaks one fails here, with the case named.
 */
class ConversationDatasetTest {

  /** The judgment each tag carries: fixed per tag by the labelling rules. */
  private static final Map<String, Boolean> TAG_JUDGMENTS = Map.of(
      "follow-up-collision", false,
      "system-prompt-collision", false,
      "end-user-collision", false,
      "template-prefix", false,
      "same-context-paraphrase", true,
      "first-turn-paraphrase", true);

  private final List<ConversationCase> cases =
      EvalDatasets.conversations(EvalDatasets.CONVERSATION_TEST);

  @Test
  @DisplayName("every case follows the labelling rule of its tag")
  void casesFollowTheLabellingRules() {
    Set<String> ids = new HashSet<>();
    for (ConversationCase conversation : cases) {
      assertTrue(ids.add(conversation.id()), "duplicate id " + conversation.id());
      assertEquals(1, conversation.tags().size(),
          conversation.id() + " must carry exactly one tag");
      String tag = conversation.tags().getFirst();
      Boolean judgment = TAG_JUDGMENTS.get(tag);
      assertNotNull(judgment, conversation.id() + " has an unknown tag: " + tag);
      assertEquals(judgment, conversation.servable(),
          conversation.id() + " is labelled against the rule of its tag " + tag);
      assertTrue(Set.of("en", "fr").contains(conversation.language()),
          conversation.id() + " has an unexpected language: " + conversation.language());
    }
  }

  @Test
  @DisplayName("collision cases collide on the last user turn, paraphrases do not repeat it")
  void lastTurnsMatchWhatTheTagClaims() {
    for (ConversationCase conversation : cases) {
      String tag = conversation.tags().getFirst();
      String stored = ConversationCase.lastUserText(conversation.stored());
      String incoming = ConversationCase.lastUserText(conversation.incoming());
      switch (tag) {
        case "follow-up-collision", "system-prompt-collision", "end-user-collision" -> {
          assertEquals(stored, incoming,
              conversation.id() + ": a " + tag + " case must repeat the last user turn");
          assertFalse(conversation.stored().equals(conversation.incoming()),
              conversation.id() + ": the two conversations are identical");
        }
        case "same-context-paraphrase", "first-turn-paraphrase" -> {
          assertFalse(stored.equals(incoming),
              conversation.id() + ": a paraphrase case must reword the last user turn");
          assertEquals(everythingButTheLastTurn(conversation.stored()),
              everythingButTheLastTurn(conversation.incoming()),
              conversation.id() + ": a paraphrase case must keep the context identical");
        }
        default -> {
          // template-prefix: checked against the tokenizer below.
        }
      }
    }
  }

  /**
   * A {@code template-prefix} case is only one if the embedding really cannot
   * see where the two prompts differ. Checked with the model's own tokenizer —
   * once with the options the application loads it with, once untruncated —
   * rather than with a character count, so the tag cannot drift from what it
   * claims when a case is edited or the embedding model changes.
   */
  @Test
  @DisplayName("template-prefix cases exceed the embedding window and share what it sees")
  void templatePrefixCasesExceedTheEmbeddingWindow() {
    try (HuggingFaceTokenizer windowed = tokenizer(Map.of("padding", "true"));
         HuggingFaceTokenizer full = tokenizer(Map.of("padding", "false",
             "truncation", "false"))) {
      List<ConversationCase> templates = cases.stream()
          .filter(conversation -> conversation.tags().contains("template-prefix"))
          .toList();
      assertFalse(templates.isEmpty(), "no template-prefix case left");

      for (ConversationCase conversation : templates) {
        String stored = ConversationCase.lastUserText(conversation.stored());
        String incoming = ConversationCase.lastUserText(conversation.incoming());
        long[] storedSeen = windowed.encode(stored).getIds();
        long[] incomingSeen = windowed.encode(incoming).getIds();

        assertTrue(full.encode(stored).getIds().length > storedSeen.length,
            conversation.id() + ": the stored prompt fits the embedding window");
        assertTrue(full.encode(incoming).getIds().length > incomingSeen.length,
            conversation.id() + ": the incoming prompt fits the embedding window");
        assertArrayEquals(storedSeen, incomingSeen,
            conversation.id() + ": the prompts differ inside the embedding window");
      }
    }
  }

  private static List<ConversationCase.Turn> everythingButTheLastTurn(
      List<ConversationCase.Turn> turns) {
    return turns.subList(0, turns.size() - 1);
  }

  private static HuggingFaceTokenizer tokenizer(Map<String, String> options) {
    String location = EvalConfig.load().embeddingTokenizerResource();
    try (InputStream in = new DefaultResourceLoader().getResource(location).getInputStream()) {
      return HuggingFaceTokenizer.newInstance(in, options);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not load the tokenizer from " + location, e);
    }
  }
}
