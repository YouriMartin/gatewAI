package com.example.gatewai.infrastructure.llm;

import java.util.List;

import com.example.gatewai.domain.model.ModelDefinition;
import com.example.gatewai.domain.model.ModelTier;
import com.example.gatewai.domain.port.out.ComplexityClassifier;
import com.example.gatewai.domain.port.out.ModelRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

@Component
class RoutingAdvisor implements CallAdvisor, StreamAdvisor {

  private static final Logger LOG =
      LoggerFactory.getLogger(RoutingAdvisor.class);

  private final ComplexityClassifier classifier;
  private final ModelRegistry modelRegistry;

  RoutingAdvisor(ComplexityClassifier classifier,
                 ModelRegistry modelRegistry) {
    this.classifier = classifier;
    this.modelRegistry = modelRegistry;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request,
                                       CallAdvisorChain chain) {
    String userText = extractUserText(request);
    if (userText == null || userText.isBlank()) {
      return chain.nextCall(request);
    }

    ModelTier tier = classifier.classify(userText);
    List<ModelDefinition> candidates = modelRegistry.findByTier(tier);

    if (candidates.isEmpty()) {
      LOG.info("No model configured for tier {}, using default", tier);
      return chain.nextCall(request);
    }

    ModelDefinition target = candidates.getFirst();
    LOG.info("Routing to {} (tier={}, model={})",
        target.provider(), tier, target.modelId());

    Prompt routedPrompt = reroutePrompt(request.prompt(),
        target.modelId());
    ChatClientRequest routedRequest = ChatClientRequest.builder()
        .prompt(routedPrompt)
        .context(request.context())
        .build();

    return chain.nextCall(routedRequest);
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                               StreamAdvisorChain chain) {
    return chain.nextStream(request);
  }

  @Override
  public String getName() {
    return "Routing";
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  private static String extractUserText(ChatClientRequest request) {
    UserMessage userMessage = request.prompt().getUserMessage();
    return userMessage != null ? userMessage.getText() : null;
  }

  private static Prompt reroutePrompt(Prompt original,
                                      String targetModelId) {
    ChatOptions originalOptions = original.getOptions();
    ChatOptions.Builder builder = ChatOptions.builder()
        .model(targetModelId);

    if (originalOptions != null) {
      if (originalOptions.getTemperature() != null) {
        builder.temperature(originalOptions.getTemperature());
      }
      if (originalOptions.getMaxTokens() != null) {
        builder.maxTokens(originalOptions.getMaxTokens());
      }
      if (originalOptions.getTopP() != null) {
        builder.topP(originalOptions.getTopP());
      }
    }

    return new Prompt(original.getInstructions(), builder.build());
  }
}
