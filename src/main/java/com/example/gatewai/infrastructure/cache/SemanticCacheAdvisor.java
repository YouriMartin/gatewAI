package com.example.gatewai.infrastructure.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.model.RequestContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

@Component
class SemanticCacheAdvisor implements CallAdvisor, StreamAdvisor {

  static final String CACHE_RESPONSE_KEY = "cached_response";
  static final String CACHE_MODEL_KEY = "cached_model";
  static final String CACHE_FINISH_REASON_KEY = "cached_finish_reason";
  static final String CACHE_PROMPT_TOKENS_KEY = "cached_prompt_tokens";
  static final String CACHE_COMPLETION_TOKENS_KEY = "cached_completion_tokens";
  static final String CREATED_AT_KEY = "created_at";
  static final String CLIENT_ID_KEY = "client_id";

  private static final Logger LOG =
      LoggerFactory.getLogger(SemanticCacheAdvisor.class);

  private final VectorStore vectorStore;
  private final SemanticCacheProperties properties;

  SemanticCacheAdvisor(VectorStore vectorStore,
                       SemanticCacheProperties properties) {
    this.vectorStore = vectorStore;
    this.properties = properties;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request,
                                       CallAdvisorChain chain) {
    String userText = extractUserText(request);
    if (userText == null || userText.isBlank()) {
      return chain.nextCall(request);
    }

    SearchRequest.Builder searchBuilder = SearchRequest.builder()
        .query(userText)
        .topK(properties.getTopK())
        .similarityThreshold(properties.getSimilarityThreshold());

    Filter.Expression filter = buildFilterExpression();
    if (filter != null) {
      searchBuilder.filterExpression(filter);
    }

    List<Document> hits = vectorStore.similaritySearch(searchBuilder.build());

    if (!hits.isEmpty()) {
      LOG.info("Cache HIT for query [{}] (score={})",
          truncate(userText), hits.getFirst().getScore());
      return buildCachedResponse(hits.getFirst(), request.context());
    }

    LOG.info("Cache MISS for query [{}]", truncate(userText));

    ChatClientResponse response = chain.nextCall(request);
    cacheStore(userText, response);
    return response;
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                               StreamAdvisorChain chain) {
    return chain.nextStream(request);
  }

  @Override
  public String getName() {
    return "SemanticCache";
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  static String extractUserText(ChatClientRequest request) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return null;
    }
    return userMessage.getText();
  }

  Filter.Expression buildFilterExpression() {
    FilterExpressionBuilder b = new FilterExpressionBuilder();
    FilterExpressionBuilder.Op combined = null;

    if (properties.isClientNamespacing() && RequestContext.CURRENT.isBound()) {
      String clientId = RequestContext.CURRENT.get().clientId();
      if (clientId != null) {
        combined = b.eq(CLIENT_ID_KEY, clientId);
      }
    }

    if (properties.getTtlMinutes() > 0) {
      long cutoff = Instant.now()
          .minus(Duration.ofMinutes(properties.getTtlMinutes()))
          .toEpochMilli();
      FilterExpressionBuilder.Op ttlFilter = b.gte(CREATED_AT_KEY, cutoff);
      combined = combined != null ? b.and(combined, ttlFilter) : ttlFilter;
    }

    return combined != null ? combined.build() : null;
  }

  private void cacheStore(String userText, ChatClientResponse response) {
    ChatResponse chatResponse = response.chatResponse();
    if (chatResponse == null) {
      return;
    }

    Generation result = chatResponse.getResult();
    if (result == null) {
      return;
    }

    AssistantMessage output = result.getOutput();
    if (output == null) {
      return;
    }

    String responseText = output.getText();
    if (responseText == null) {
      return;
    }

    Map<String, Object> metadata = new HashMap<>();
    metadata.put(CACHE_RESPONSE_KEY, responseText);
    metadata.put(CREATED_AT_KEY, Instant.now().toEpochMilli());

    ChatResponseMetadata responseMetadata = chatResponse.getMetadata();
    if (responseMetadata != null && responseMetadata.getModel() != null) {
      metadata.put(CACHE_MODEL_KEY, responseMetadata.getModel());
    }

    ChatGenerationMetadata resultMetadata = result.getMetadata();
    if (resultMetadata != null && resultMetadata.getFinishReason() != null) {
      metadata.put(CACHE_FINISH_REASON_KEY, resultMetadata.getFinishReason());
    }

    if (responseMetadata != null && responseMetadata.getUsage() != null) {
      Usage usage = responseMetadata.getUsage();
      metadata.put(CACHE_PROMPT_TOKENS_KEY, intOrZero(usage.getPromptTokens()));
      metadata.put(CACHE_COMPLETION_TOKENS_KEY,
          intOrZero(usage.getCompletionTokens()));
    }

    if (RequestContext.CURRENT.isBound()) {
      String clientId = RequestContext.CURRENT.get().clientId();
      if (clientId != null) {
        metadata.put(CLIENT_ID_KEY, clientId);
      }
    }

    vectorStore.add(List.of(new Document(userText, metadata)));
  }

  private static ChatClientResponse buildCachedResponse(Document hit,
                                                        Map<String, Object> context) {
    Map<String, Object> metadata = hit.getMetadata();
    String responseText = (String) metadata.get(CACHE_RESPONSE_KEY);
    String model = (String) metadata.getOrDefault(CACHE_MODEL_KEY, "cache");
    String finishReason = (String) metadata.getOrDefault(
        CACHE_FINISH_REASON_KEY, "stop");

    int promptTokens = intOrZero(metadata.get(CACHE_PROMPT_TOKENS_KEY));
    int completionTokens = intOrZero(metadata.get(CACHE_COMPLETION_TOKENS_KEY));

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
        .build();

    ChatResponse chatResponse = new ChatResponse(
        List.of(generation), responseMeta);

    return ChatClientResponse.builder()
        .chatResponse(chatResponse)
        .context(context)
        .build();
  }

  private static int intOrZero(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static String truncate(String text) {
    int maxLen = 80;
    if (text.length() <= maxLen) {
      return text;
    }
    return text.substring(0, maxLen) + "...";
  }
}
