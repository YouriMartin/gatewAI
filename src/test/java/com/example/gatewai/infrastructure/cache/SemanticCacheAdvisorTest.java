package com.example.gatewai.infrastructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.gatewai.domain.model.RequestContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.Ordered;

import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class SemanticCacheAdvisorTest {

  @Mock
  private VectorStore vectorStore;

  @Mock
  private CallAdvisorChain callChain;

  @Mock
  private StreamAdvisorChain streamChain;

  @Mock
  private ChatClientResponse chainResponse;

  @Captor
  private ArgumentCaptor<SearchRequest> searchRequestCaptor;

  @Captor
  private ArgumentCaptor<List<Document>> documentsCaptor;

  private SemanticCacheAdvisor advisor;

  @BeforeEach
  void setUp() {
    advisor = new SemanticCacheAdvisor(vectorStore);
  }

  // ---- Cache hit tests ----

  @Test
  void cacheHitReturnsStoredResponseWithoutCallingChain() {
    ChatClientRequest request = buildRequest("What is Spring?");
    Document cachedDoc = new Document("What is Spring?", Map.of(
        SemanticCacheAdvisor.CACHE_RESPONSE_KEY, "Spring is a framework.",
        SemanticCacheAdvisor.CACHE_MODEL_KEY, "claude-3-sonnet",
        SemanticCacheAdvisor.CACHE_FINISH_REASON_KEY, "end_turn"
    ));

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(cachedDoc));

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    verify(callChain, never()).nextCall(any());
    verify(vectorStore, never()).add(any());
    assertNotNull(result.chatResponse());
    assertEquals("Spring is a framework.",
        result.chatResponse().getResult().getOutput().getText());
    assertEquals("claude-3-sonnet",
        result.chatResponse().getMetadata().getModel());
    assertEquals("end_turn",
        result.chatResponse().getResult().getMetadata().getFinishReason());
    assertEquals(0, result.chatResponse().getMetadata().getUsage().getTotalTokens());
  }

  @Test
  void cacheHitUsesDefaultsWhenMetadataIsMissing() {
    ChatClientRequest request = buildRequest("test question");
    Document cachedDoc = new Document("test question", Map.of(
        SemanticCacheAdvisor.CACHE_RESPONSE_KEY, "cached answer"
    ));

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(cachedDoc));

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    assertEquals("cache",
        result.chatResponse().getMetadata().getModel());
    assertEquals("stop",
        result.chatResponse().getResult().getMetadata().getFinishReason());
  }

  // ---- Cache miss + store tests ----

  @Test
  void cacheMissDelegatesToChainAndStoresResponse() {
    ChatClientRequest request = buildRequest("What is Quarkus?");
    ChatClientResponse llmResponse = buildLlmResponse(
        "Quarkus is a framework.", "claude-3-sonnet", "end_turn");

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(llmResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    assertSame(llmResponse, result);
    verify(callChain).nextCall(request);

    verify(vectorStore).add(documentsCaptor.capture());
    List<Document> stored = documentsCaptor.getValue();
    assertEquals(1, stored.size());

    Document doc = stored.getFirst();
    assertEquals("What is Quarkus?", doc.getText());
    assertEquals("Quarkus is a framework.",
        doc.getMetadata().get(SemanticCacheAdvisor.CACHE_RESPONSE_KEY));
    assertEquals("claude-3-sonnet",
        doc.getMetadata().get(SemanticCacheAdvisor.CACHE_MODEL_KEY));
    assertEquals("end_turn",
        doc.getMetadata().get(SemanticCacheAdvisor.CACHE_FINISH_REASON_KEY));
    assertNotNull(doc.getMetadata().get("created_at"));
  }

  @Test
  void cacheMissStoresClientIdFromScopedValue() {
    ChatClientRequest request = buildRequest("Hello");
    ChatClientResponse llmResponse = buildLlmResponse("Hi!", "claude-3", "stop");

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(llmResponse);

    RequestContext ctx = new RequestContext("tenant-42", "trace-1");
    ScopedValue.where(RequestContext.CURRENT, ctx).run(() ->
        advisor.adviseCall(request, callChain)
    );

    verify(vectorStore).add(documentsCaptor.capture());
    Document doc = documentsCaptor.getValue().getFirst();
    assertEquals("tenant-42", doc.getMetadata().get("client_id"));
  }

  @Test
  void cacheMissWithoutScopedValueOmitsClientId() {
    ChatClientRequest request = buildRequest("Hello");
    ChatClientResponse llmResponse = buildLlmResponse("Hi!", "claude-3", "stop");

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(llmResponse);

    advisor.adviseCall(request, callChain);

    verify(vectorStore).add(documentsCaptor.capture());
    Document doc = documentsCaptor.getValue().getFirst();
    assertTrue(!doc.getMetadata().containsKey("client_id"));
  }

  @Test
  void cacheMissWithNullResponseTextDoesNotStore() {
    ChatClientRequest request = buildRequest("Hello");
    ChatClientResponse llmResponse = buildLlmResponse(null, "claude-3", "stop");

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(llmResponse);

    advisor.adviseCall(request, callChain);

    verify(vectorStore, never()).add(any());
  }

  // ---- Search parameters ----

  @Test
  void searchUsesCorrectQueryAndThreshold() {
    ChatClientRequest request = buildRequest("Hello world");
    ChatClientResponse llmResponse = buildLlmResponse("Hi!", "claude-3", "stop");

    when(vectorStore.similaritySearch(searchRequestCaptor.capture()))
        .thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(llmResponse);

    advisor.adviseCall(request, callChain);

    SearchRequest captured = searchRequestCaptor.getValue();
    assertEquals("Hello world", captured.getQuery());
    assertEquals(SemanticCacheAdvisor.TOP_K, captured.getTopK());
    assertEquals(SemanticCacheAdvisor.SIMILARITY_THRESHOLD,
        captured.getSimilarityThreshold());
  }

  // ---- Skip-cache tests ----

  @Test
  void blankUserTextSkipsCacheAndDelegatesToChain() {
    ChatClientRequest request = buildRequest("   ");

    when(callChain.nextCall(request)).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    verify(callChain).nextCall(request);
    assertSame(chainResponse, result);
  }

  @Test
  void noUserMessageSkipsCacheAndDelegatesToChain() {
    Prompt prompt = new Prompt(List.of());
    ChatClientRequest request = ChatClientRequest.builder()
        .prompt(prompt)
        .context(Map.of())
        .build();

    when(callChain.nextCall(request)).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    verify(callChain).nextCall(request);
    assertSame(chainResponse, result);
  }

  // ---- Stream / metadata tests ----

  @Test
  void adviseStreamDelegatesToChain() {
    ChatClientRequest request = buildRequest("stream test");
    Flux<ChatClientResponse> flux = Flux.just(chainResponse);
    when(streamChain.nextStream(request)).thenReturn(flux);

    Flux<ChatClientResponse> result = advisor.adviseStream(request, streamChain);

    assertSame(flux, result);
    verify(streamChain).nextStream(request);
  }

  @Test
  void nameIsSemanticCache() {
    assertEquals("SemanticCache", advisor.getName());
  }

  @Test
  void orderIsHighestPrecedence() {
    assertEquals(Ordered.HIGHEST_PRECEDENCE, advisor.getOrder());
  }

  // ---- Helpers ----

  private static ChatClientRequest buildRequest(String userText) {
    Prompt prompt = new Prompt(new UserMessage(userText));
    return ChatClientRequest.builder()
        .prompt(prompt)
        .context(Map.of())
        .build();
  }

  private static ChatClientResponse buildLlmResponse(String text,
                                                      String model,
                                                      String finishReason) {
    Generation generation = new Generation(
        new AssistantMessage(text),
        ChatGenerationMetadata.builder()
            .finishReason(finishReason)
            .build()
    );

    ChatResponseMetadata meta = ChatResponseMetadata.builder()
        .model(model)
        .usage(new DefaultUsage(10, 5))
        .build();

    ChatResponse chatResponse = new ChatResponse(List.of(generation), meta);
    return ChatClientResponse.builder()
        .chatResponse(chatResponse)
        .context(Map.of())
        .build();
  }
}
