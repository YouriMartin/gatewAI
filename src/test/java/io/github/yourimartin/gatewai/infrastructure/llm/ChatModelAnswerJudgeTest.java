package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.port.out.AnswerJudge;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class ChatModelAnswerJudgeTest {

  @Test
  void callsTheNamedModelDirectlyAtTemperatureZeroWithoutFailover() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
        List.of(new Generation(new AssistantMessage("NO"))),
        ChatResponseMetadata.builder().usage(new DefaultUsage(120, 1)).build()));

    AnswerJudge.Reply reply = new ChatModelAnswerJudge(chatModel).judge("judge-model",
        List.of(new LlmMessage("system", "rubric"), new LlmMessage("user", "the case")));

    assertThat(reply.text()).isEqualTo("NO");
    assertThat(reply.usage()).isEqualTo(new TokenUsage(120, 1, 121, null));
    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    assertThat(prompt.getValue().getOptions().getModel()).isEqualTo("judge-model");
    assertThat(prompt.getValue().getOptions().getTemperature()).isEqualTo(0.0);
    assertThat(GatewaiChatOptions.failoverOf(prompt.getValue().getOptions())).isFalse();
    assertThat(prompt.getValue().getInstructions()).extracting(m -> m.getMessageType())
        .containsExactly(MessageType.SYSTEM, MessageType.USER);
  }
}
