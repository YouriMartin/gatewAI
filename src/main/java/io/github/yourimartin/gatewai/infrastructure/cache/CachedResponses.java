package io.github.yourimartin.gatewai.infrastructure.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;

import reactor.core.publisher.Flux;

/**
 * The responses the cache produces itself: a cached answer replayed as one
 * response or as a chunk stream, with no model call (Phase 7.5), and the cache
 * outcome stamped on a response it did not produce (v4 A.2).
 *
 * <p>Split out of {@link SemanticCacheAdvisor} when the advisor grew the
 * conformal decision (v2 batch 3): the advisor should read as cache logic, and
 * this is response plumbing.
 *
 * <p>The pieces are fixed-size rather than word-aligned so the deltas
 * concatenate back to the exact stored answer, byte for byte.
 */
final class CachedResponses {

  /** Characters per synthetic chunk. */
  private static final int CHUNK_SIZE = 24;

  private CachedResponses() {
  }

  /** Replays a cached answer as one response, with no model call. */
  static ChatClientResponse call(Document hit, Map<String, Object> context) {
    Map<String, Object> metadata = hit.getMetadata();
    String responseText = (String) metadata.get(SemanticCacheAdvisor.CACHE_RESPONSE_KEY);
    String model = (String) metadata.getOrDefault(SemanticCacheAdvisor.CACHE_MODEL_KEY, "cache");
    String finishReason = (String) metadata.getOrDefault(
        SemanticCacheAdvisor.CACHE_FINISH_REASON_KEY, "stop");

    int promptTokens = intOrZero(metadata.get(SemanticCacheAdvisor.CACHE_PROMPT_TOKENS_KEY));
    int completionTokens = intOrZero(metadata.get(SemanticCacheAdvisor.CACHE_COMPLETION_TOKENS_KEY));

    Generation generation = new Generation(
        new AssistantMessage(responseText),
        ChatGenerationMetadata.builder()
            .finishReason(finishReason)
            .build()
    );

    // Replay the original token counts and flag the hit, so green accounting
    // can credit the avoided premium inference (real emission stays zero).
    ChatResponseMetadata responseMeta = ChatResponseMetadata.builder()
        .model(model)
        .usage(new DefaultUsage(promptTokens, completionTokens))
        .keyValue(LlmResponse.CACHE_HIT_METADATA_KEY, Boolean.TRUE)
        .keyValue(LlmResponse.CACHE_OUTCOME_METADATA_KEY, CacheOutcome.HIT.name())
        .build();

    ChatResponse chatResponse = new ChatResponse(
        List.of(generation), responseMeta);

    return ChatClientResponse.builder()
        .chatResponse(chatResponse)
        .context(context)
        .build();
  }

  /** Records what the cache did on a response it did not produce, for the header. */
  static ChatClientResponse withOutcome(ChatClientResponse response,
                                        CacheOutcome outcome) {
    if (response == null || response.chatResponse() == null) {
      return response;
    }
    ChatResponse flagged = ChatResponse.builder()
        .from(response.chatResponse())
        .metadata(LlmResponse.CACHE_OUTCOME_METADATA_KEY, outcome.name())
        .build();
    return ChatClientResponse.builder()
        .chatResponse(flagged)
        .context(response.context())
        .build();
  }

  /** Replays a cached answer as a chunk stream. */
  static Flux<ChatClientResponse> stream(Document hit, Map<String, Object> context) {
    Map<String, Object> metadata = hit.getMetadata();
    String responseText =
        (String) metadata.getOrDefault(SemanticCacheAdvisor.CACHE_RESPONSE_KEY, "");
    String model =
        (String) metadata.getOrDefault(SemanticCacheAdvisor.CACHE_MODEL_KEY, "cache");
    String finishReason = (String) metadata.getOrDefault(
        SemanticCacheAdvisor.CACHE_FINISH_REASON_KEY, "stop");
    int promptTokens =
        intOrZero(metadata.get(SemanticCacheAdvisor.CACHE_PROMPT_TOKENS_KEY));
    int completionTokens =
        intOrZero(metadata.get(SemanticCacheAdvisor.CACHE_COMPLETION_TOKENS_KEY));

    List<String> pieces = split(responseText);
    List<ChatClientResponse> chunks = new ArrayList<>();
    for (int i = 0; i < pieces.size(); i++) {
      boolean last = i == pieces.size() - 1;
      chunks.add(chunk(pieces.get(i), model, last ? finishReason : "",
          last, promptTokens, completionTokens, context));
    }
    if (chunks.isEmpty()) {
      chunks.add(chunk("", model, finishReason, true,
          promptTokens, completionTokens, context));
    }
    return Flux.fromIterable(chunks);
  }

  private static ChatClientResponse chunk(String text, String model,
      String finishReason, boolean last, int promptTokens, int completionTokens,
      Map<String, Object> context) {
    Generation generation = new Generation(
        new AssistantMessage(text),
        ChatGenerationMetadata.builder()
            .finishReason(finishReason == null ? "" : finishReason)
            .build());

    var metaBuilder = ChatResponseMetadata.builder().model(model);
    if (last) {
      metaBuilder.usage(new DefaultUsage(promptTokens, completionTokens))
          .keyValue(LlmResponse.CACHE_HIT_METADATA_KEY, Boolean.TRUE)
          .keyValue(LlmResponse.CACHE_OUTCOME_METADATA_KEY, CacheOutcome.HIT.name());
    }

    ChatResponse chatResponse =
        new ChatResponse(List.of(generation), metaBuilder.build());
    return ChatClientResponse.builder()
        .chatResponse(chatResponse)
        .context(context)
        .build();
  }

  private static List<String> split(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    List<String> pieces = new ArrayList<>();
    for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
      pieces.add(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE)));
    }
    return pieces;
  }

  private static int intOrZero(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }
}
