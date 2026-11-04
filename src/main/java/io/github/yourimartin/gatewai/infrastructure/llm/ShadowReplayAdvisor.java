package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * The last advisor before the model (v4 D.1): when the request is a shadow
 * replay, answers with what the client already received instead of calling the
 * egress. The cache and the router above it run unchanged, so a shadow decision
 * is taken by the very code that would have taken it in {@code ENFORCE} — and the
 * cache stores the real answer, under its own rules, as it would have.
 *
 * <p>Inert for every other request: the replay key is set only by
 * {@link SpringAiLlmClient#replay}, never from anything a client sends.
 */
@Component
class ShadowReplayAdvisor implements CallAdvisor {

  /** Advisor context key holding the {@link RelayedAnswer} to replay. */
  static final String REPLAY_KEY = "gatewai.shadow.replay";

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    if (!(request.context().get(REPLAY_KEY) instanceof RelayedAnswer answer)) {
      return chain.nextCall(request);
    }
    return ChatClientResponse.builder()
        .chatResponse(toChatResponse(answer))
        .context(request.context())
        .build();
  }

  static ChatResponse toChatResponse(RelayedAnswer answer) {
    Generation generation = new Generation(
        new AssistantMessage(answer.text()),
        ChatGenerationMetadata.builder().finishReason(answer.finishReason()).build());
    TokenUsage usage = answer.usage() == null ? TokenUsage.NONE : answer.usage();
    ChatResponseMetadata metadata = ChatResponseMetadata.builder()
        .model(answer.model())
        .usage(new DefaultUsage(usage.promptTokens(), usage.completionTokens(),
            usage.totalTokens()))
        .build();
    return new ChatResponse(List.of(generation), metadata);
  }

  @Override
  public String getName() {
    return "ShadowReplay";
  }

  /** Below the cache and the router, above only the model call itself. */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 1;
  }
}
