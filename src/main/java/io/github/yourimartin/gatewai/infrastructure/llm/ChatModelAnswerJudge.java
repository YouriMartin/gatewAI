package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.List;
import java.util.Objects;

import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.port.out.AnswerJudge;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * The LLM judge of v4 D.2, called on the egress {@link ChatModel} directly — the
 * same delegating model the chain ends on, but without the advisor chain: no
 * cache to answer it from a cached verdict, no router to move it to another
 * model. Temperature 0, and no failover: a verdict comes from the model named.
 */
@Component
class ChatModelAnswerJudge implements AnswerJudge {

  private final ChatModel chatModel;

  ChatModelAnswerJudge(ChatModel chatModel) {
    this.chatModel = chatModel;
  }

  @Override
  public Reply judge(String modelId, List<LlmMessage> prompt) {
    List<Message> messages = prompt.stream().map(ChatModelAnswerJudge::toMessage).toList();
    ChatResponse response = Objects.requireNonNull(chatModel.call(new Prompt(messages,
        GatewaiChatOptions.builder().model(modelId).temperature(0.0).build())),
        "the judge returned no response");
    Generation result = response.getResult();
    AssistantMessage output = result == null ? null : result.getOutput();
    String text = output == null ? null : output.getText();
    return new Reply(text, usage(response));
  }

  private static TokenUsage usage(ChatResponse response) {
    Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
    if (usage == null) {
      return TokenUsage.NONE;
    }
    int prompt = SpringAiLlmClient.promptTokens(usage);
    int completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    return new TokenUsage(prompt, completion, prompt + completion,
        SpringAiLlmClient.cachedPromptTokens(usage));
  }

  private static Message toMessage(LlmMessage message) {
    return "system".equals(message.role())
        ? new SystemMessage(message.content()) : new UserMessage(message.content());
  }
}
