package com.example.gatewai.infrastructure.cache;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.gatewai.domain.model.RequestContext;

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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

@Component
class SemanticCacheAdvisor implements CallAdvisor, StreamAdvisor {

  static final String CACHE_RESPONSE_KEY = "cached_response";
  static final String CACHE_MODEL_KEY = "cached_model";
  static final String CACHE_FINISH_REASON_KEY = "cached_finish_reason";
  static final double SIMILARITY_THRESHOLD = 0.92;
  static final int TOP_K = 1;

  private final VectorStore vectorStore;

  SemanticCacheAdvisor(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request,
                                       CallAdvisorChain chain) {
    String userText = extractUserText(request);
    if (userText == null || userText.isBlank()) {
      return chain.nextCall(request);
    }

    List<Document> hits = vectorStore.similaritySearch(
        SearchRequest.builder()
            .query(userText)
            .topK(TOP_K)
            .similarityThreshold(SIMILARITY_THRESHOLD)
            .build()
    );

    if (!hits.isEmpty()) {
      return buildCachedResponse(hits.getFirst(), request.context());
    }

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

  private void cacheStore(String userText, ChatClientResponse response) {
    ChatResponse chatResponse = response.chatResponse();
    if (chatResponse == null || chatResponse.getResult() == null) {
      return;
    }

    String responseText = chatResponse.getResult().getOutput().getText();
    if (responseText == null) {
      return;
    }

    Map<String, Object> metadata = new HashMap<>();
    metadata.put(CACHE_RESPONSE_KEY, responseText);
    metadata.put("created_at", Instant.now().toString());

    if (chatResponse.getMetadata() != null
        && chatResponse.getMetadata().getModel() != null) {
      metadata.put(CACHE_MODEL_KEY, chatResponse.getMetadata().getModel());
    }

    if (chatResponse.getResult().getMetadata() != null
        && chatResponse.getResult().getMetadata().getFinishReason() != null) {
      metadata.put(CACHE_FINISH_REASON_KEY,
          chatResponse.getResult().getMetadata().getFinishReason());
    }

    if (RequestContext.CURRENT.isBound()) {
      String clientId = RequestContext.CURRENT.get().clientId();
      if (clientId != null) {
        metadata.put("client_id", clientId);
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

    Generation generation = new Generation(
        new AssistantMessage(responseText),
        ChatGenerationMetadata.builder()
            .finishReason(finishReason)
            .build()
    );

    ChatResponseMetadata responseMeta = ChatResponseMetadata.builder()
        .model(model)
        .usage(new DefaultUsage(0, 0))
        .build();

    ChatResponse chatResponse = new ChatResponse(
        List.of(generation), responseMeta);

    return ChatClientResponse.builder()
        .chatResponse(chatResponse)
        .context(context)
        .build();
  }
}
