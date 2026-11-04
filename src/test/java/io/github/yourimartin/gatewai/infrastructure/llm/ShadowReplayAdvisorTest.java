package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

class ShadowReplayAdvisorTest {

  private final ShadowReplayAdvisor advisor = new ShadowReplayAdvisor();
  private final CallAdvisorChain chain = mock(CallAdvisorChain.class);

  @Test
  void aReplayIsAnsweredWithTheRelayedAnswerAndNoModelIsCalled() {
    RelayedAnswer answer = new RelayedAnswer("stub-premium", "Hello.", "stop",
        new TokenUsage(3, 2, 5));
    ChatClientRequest request = ChatClientRequest.builder()
        .prompt(new Prompt("Hi"))
        .context(Map.of(ShadowReplayAdvisor.REPLAY_KEY, answer))
        .build();

    ChatResponse response = advisor.adviseCall(request, chain).chatResponse();

    verifyNoInteractions(chain);
    assertThat(response.getResult().getOutput().getText()).isEqualTo("Hello.");
    assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
    assertThat(response.getMetadata().getModel()).isEqualTo("stub-premium");
    assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(3);
    assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(2);
  }

  @Test
  void anyOtherRequestGoesOnToTheModel() {
    ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("Hi")).build();
    ChatClientResponse fromModel = ChatClientResponse.builder().build();
    when(chain.nextCall(request)).thenReturn(fromModel);

    assertThat(advisor.adviseCall(request, chain)).isSameAs(fromModel);
    verify(chain).nextCall(request);
  }

  @Test
  void sitsBelowTheCacheAndTheRouter() {
    assertThat(advisor.getOrder()).isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1);
  }
}
