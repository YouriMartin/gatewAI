package com.example.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.gatewai.domain.model.ModelDefinition;
import com.example.gatewai.domain.model.ModelTier;
import com.example.gatewai.domain.port.out.ComplexityClassifier;
import com.example.gatewai.domain.port.out.ModelRegistry;

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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class RoutingAdvisorTest {

  @Mock
  private ComplexityClassifier classifier;

  @Mock
  private ModelRegistry modelRegistry;

  @Mock
  private CallAdvisorChain callChain;

  @Mock
  private StreamAdvisorChain streamChain;

  @Mock
  private ChatClientResponse chainResponse;

  @Captor
  private ArgumentCaptor<ChatClientRequest> requestCaptor;

  private RoutingAdvisor advisor;

  @BeforeEach
  void setUp() {
    advisor = new RoutingAdvisor(classifier, modelRegistry);
  }

  // ---- Routing tests ----

  @Test
  void routesToPremiumModelForComplexQuery() {
    ChatClientRequest request = buildRequest("Refactor this class");
    ModelDefinition sonnet = premiumModel();

    when(classifier.classify("Refactor this class"))
        .thenReturn(ModelTier.CLOUD_PREMIUM);
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(sonnet));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    ChatClientRequest routed = requestCaptor.getValue();
    assertEquals("claude-sonnet-4-20250514",
        routed.prompt().getOptions().getModel());
  }

  @Test
  void routesToEntryModelForMediumQuery() {
    ChatClientRequest request = buildRequest(
        "x".repeat(150));
    ModelDefinition haiku = entryModel();

    when(classifier.classify(any())).thenReturn(ModelTier.CLOUD_ENTRY);
    when(modelRegistry.findByTier(ModelTier.CLOUD_ENTRY))
        .thenReturn(List.of(haiku));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    assertEquals("claude-haiku-4-20250506",
        requestCaptor.getValue().prompt().getOptions().getModel());
  }

  @Test
  void preservesOriginalOptionsExceptModel() {
    Prompt prompt = new Prompt(
        List.of(new UserMessage("Refactor this")),
        ChatOptions.builder()
            .model("original-model")
            .temperature(0.7)
            .maxTokens(256)
            .build()
    );
    ChatClientRequest request = ChatClientRequest.builder()
        .prompt(prompt)
        .context(Map.of())
        .build();

    when(classifier.classify(any())).thenReturn(ModelTier.CLOUD_PREMIUM);
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    ChatOptions routedOptions =
        requestCaptor.getValue().prompt().getOptions();
    assertEquals("claude-sonnet-4-20250514", routedOptions.getModel());
    assertEquals(0.7, routedOptions.getTemperature());
    assertEquals(256, routedOptions.getMaxTokens());
  }

  // ---- Fallback tests ----

  @Test
  void fallsBackToDefaultWhenNoModelForTier() {
    ChatClientRequest request = buildRequest("Hello");

    when(classifier.classify("Hello")).thenReturn(ModelTier.LOCAL);
    when(modelRegistry.findByTier(ModelTier.LOCAL))
        .thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(request);
    assertSame(chainResponse, result);
  }

  // ---- Skip-routing tests ----

  @Test
  void blankTextSkipsRoutingAndPassesThrough() {
    ChatClientRequest request = buildRequest("   ");

    when(callChain.nextCall(request)).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    assertSame(chainResponse, result);
  }

  @Test
  void noUserMessageSkipsRouting() {
    Prompt prompt = new Prompt(List.of());
    ChatClientRequest request = ChatClientRequest.builder()
        .prompt(prompt)
        .context(Map.of())
        .build();

    when(callChain.nextCall(request)).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    assertSame(chainResponse, result);
  }

  // ---- Context preservation ----

  @Test
  void preservesContextInRoutedRequest() {
    Map<String, Object> context = Map.of("key", "value");
    ChatClientRequest request = ChatClientRequest.builder()
        .prompt(new Prompt(new UserMessage("Refactor")))
        .context(context)
        .build();

    when(classifier.classify(any())).thenReturn(ModelTier.CLOUD_PREMIUM);
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    assertEquals(context, requestCaptor.getValue().context());
  }

  // ---- Stream / metadata tests ----

  @Test
  void adviseStreamPassesThrough() {
    ChatClientRequest request = buildRequest("stream test");
    Flux<ChatClientResponse> flux = Flux.just(chainResponse);
    when(streamChain.nextStream(request)).thenReturn(flux);

    Flux<ChatClientResponse> result =
        advisor.adviseStream(request, streamChain);

    assertSame(flux, result);
  }

  @Test
  void nameIsRouting() {
    assertEquals("Routing", advisor.getName());
  }

  @Test
  void orderIsAfterCache() {
    assertEquals(Ordered.HIGHEST_PRECEDENCE + 1, advisor.getOrder());
  }

  // ---- Messages preservation ----

  @Test
  void preservesAllMessagesInRoutedPrompt() {
    Prompt prompt = new Prompt(List.of(
        new UserMessage("Refactor"),
        new UserMessage("this code")
    ));
    ChatClientRequest request = ChatClientRequest.builder()
        .prompt(prompt)
        .context(Map.of())
        .build();

    when(classifier.classify(any())).thenReturn(ModelTier.CLOUD_PREMIUM);
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    assertNotNull(requestCaptor.getValue().prompt().getInstructions());
    assertEquals(2,
        requestCaptor.getValue().prompt().getInstructions().size());
  }

  // ---- Helpers ----

  private static ChatClientRequest buildRequest(String userText) {
    Prompt prompt = new Prompt(new UserMessage(userText));
    return ChatClientRequest.builder()
        .prompt(prompt)
        .context(Map.of())
        .build();
  }

  private static ModelDefinition premiumModel() {
    return new ModelDefinition(
        "claude-sonnet", "anthropic",
        "claude-sonnet-4-20250514", 0.015, 0.6,
        ModelTier.CLOUD_PREMIUM);
  }

  private static ModelDefinition entryModel() {
    return new ModelDefinition(
        "claude-haiku", "anthropic",
        "claude-haiku-4-20250506", 0.002, 0.15,
        ModelTier.CLOUD_ENTRY);
  }
}
