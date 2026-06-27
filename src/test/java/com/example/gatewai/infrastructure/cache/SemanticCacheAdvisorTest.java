package com.example.gatewai.infrastructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

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
import org.springframework.ai.chat.messages.UserMessage;
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

  private SemanticCacheAdvisor advisor;

  @BeforeEach
  void setUp() {
    advisor = new SemanticCacheAdvisor(vectorStore);
  }

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
  void cacheMissDelegatesToChain() {
    ChatClientRequest request = buildRequest("What is Quarkus?");

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(request);
    assertSame(chainResponse, result);
  }

  @Test
  void searchUsesCorrectQueryAndThreshold() {
    ChatClientRequest request = buildRequest("Hello world");

    when(vectorStore.similaritySearch(searchRequestCaptor.capture()))
        .thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    SearchRequest captured = searchRequestCaptor.getValue();
    assertEquals("Hello world", captured.getQuery());
    assertEquals(SemanticCacheAdvisor.TOP_K, captured.getTopK());
    assertEquals(SemanticCacheAdvisor.SIMILARITY_THRESHOLD,
        captured.getSimilarityThreshold());
  }

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

  private static ChatClientRequest buildRequest(String userText) {
    Prompt prompt = new Prompt(new UserMessage(userText));
    return ChatClientRequest.builder()
        .prompt(prompt)
        .context(Map.of())
        .build();
  }
}
