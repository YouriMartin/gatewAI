package io.github.yourimartin.gatewai.infrastructure.cache;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.cache.CacheScope;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecisionReason;
import io.github.yourimartin.gatewai.domain.model.decision.PromptHash;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.port.out.EmbeddingWindow;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * One request, as the semantic cache sees it (ADR 0014, v4 A.2): computed once
 * and shared by the call and stream paths, so they cannot drift apart.
 *
 * @param userText  the last user turn — the only text compared by similarity
 * @param scope     the conversation scope hash, or null on an empty prompt
 * @param turnHash  SHA-256 of the full last turn
 * @param exactOnly true when the turn is longer than the embedding window, so
 *                  only an identical turn may match
 * @param maxTokens the request's {@code max_tokens}, or null
 * @param bypass    why the cache stays out of this request, or null
 */
record CacheLookup(String userText, String scope, String turnHash, boolean exactOnly,
                   Integer maxTokens, CacheDecisionReason bypass) {

  static CacheLookup describe(ChatClientRequest request, int maxHistoryMessages,
                              ModelRegistry modelRegistry, EmbeddingWindow embeddingWindow) {
    List<Message> messages = request.prompt().getInstructions();
    int lastUser = lastUserIndex(messages);
    String userText = lastUser < 0 ? null : messages.get(lastUser).getText();
    if (userText == null || userText.isBlank()) {
      return bypass(userText, CacheDecisionReason.EMPTY_PROMPT, null);
    }

    ChatOptions options = request.prompt().getOptions();
    String scope = CacheScope.of(context(messages, lastUser),
        pinnedModel(options, modelRegistry), endUser(request),
        options == null ? null : options.getStopSequences());

    long nonSystem = messages.stream()
        .filter(message -> message.getMessageType() != MessageType.SYSTEM)
        .count();
    if (nonSystem > maxHistoryMessages) {
      return bypass(userText, CacheDecisionReason.HISTORY_TOO_LONG, scope);
    }

    return new CacheLookup(userText, scope, PromptHash.of(userText),
        !embeddingWindow.fits(userText), options == null ? null : options.getMaxTokens(),
        null);
  }

  private static CacheLookup bypass(String userText, CacheDecisionReason reason,
                                    String scope) {
    return new CacheLookup(userText, scope, null, false, null, reason);
  }

  /** The last user turn, as {@code Prompt.getUserMessage()} picks it. */
  private static int lastUserIndex(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof UserMessage) {
        return i;
      }
    }
    return -1;
  }

  /** Every message except the last user turn, in order: the context it is asked in. */
  private static List<CacheScope.Turn> context(List<Message> messages, int lastUser) {
    List<CacheScope.Turn> context = new ArrayList<>(messages.size());
    for (int i = 0; i < messages.size(); i++) {
      if (i != lastUser) {
        Message message = messages.get(i);
        context.add(new CacheScope.Turn(message.getMessageType().getValue(),
            message.getText()));
      }
    }
    return context;
  }

  /**
   * The requested model when the registry knows it, else null. Unregistered
   * names are routed, so they share a scope; a registered id is a pin, and an
   * answer from another model does not honour it.
   */
  private static String pinnedModel(ChatOptions options, ModelRegistry modelRegistry) {
    String requested = options == null ? null : options.getModel();
    if (requested == null || requested.isBlank()) {
      return null;
    }
    return modelRegistry.findByModelId(requested).isPresent() ? requested : null;
  }

  private static String endUser(ChatClientRequest request) {
    return request.context().get(LlmRequest.END_USER_CONTEXT_KEY) instanceof String user
        && !user.isBlank() ? user : null;
  }
}
