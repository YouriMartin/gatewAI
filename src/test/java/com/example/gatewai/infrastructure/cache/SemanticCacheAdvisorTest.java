package com.example.gatewai.infrastructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
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
  private ChatClientRequest request;

  @Mock
  private ChatClientResponse response;

  @Test
  void adviseCallDelegatesToChain() {
    SemanticCacheAdvisor advisor = new SemanticCacheAdvisor(vectorStore);
    when(callChain.nextCall(request)).thenReturn(response);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    assertSame(response, result);
    verify(callChain).nextCall(request);
  }

  @Test
  void adviseStreamDelegatesToChain() {
    SemanticCacheAdvisor advisor = new SemanticCacheAdvisor(vectorStore);
    Flux<ChatClientResponse> flux = Flux.just(response);
    when(streamChain.nextStream(request)).thenReturn(flux);

    Flux<ChatClientResponse> result = advisor.adviseStream(request, streamChain);

    assertSame(flux, result);
    verify(streamChain).nextStream(request);
  }

  @Test
  void nameIsSemanticCache() {
    SemanticCacheAdvisor advisor = new SemanticCacheAdvisor(vectorStore);

    assertEquals("SemanticCache", advisor.getName());
  }

  @Test
  void orderIsHighestPrecedence() {
    SemanticCacheAdvisor advisor = new SemanticCacheAdvisor(vectorStore);

    assertEquals(Ordered.HIGHEST_PRECEDENCE, advisor.getOrder());
  }

  @Test
  void vectorStoreIsAccessible() {
    SemanticCacheAdvisor advisor = new SemanticCacheAdvisor(vectorStore);

    assertSame(vectorStore, advisor.getVectorStore());
  }
}
