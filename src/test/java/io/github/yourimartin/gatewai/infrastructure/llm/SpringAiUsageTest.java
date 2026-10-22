package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

/**
 * How the chain reads cached prompt tokens from Spring AI's usage (v4 B.6): one
 * meaning for every provider, the cached tokens counted inside the prompt.
 */
class SpringAiUsageTest {

  @Test
  void openAiCountsCachedTokensInsideThePromptAlready() {
    // OpenAiChatModel: prompt_tokens includes prompt_tokens_details.cached_tokens.
    DefaultUsage usage = new DefaultUsage(10_000, 50, 10_050, new Object(), 8_000L, null);

    assertThat(SpringAiLlmClient.promptTokens(usage)).isEqualTo(10_000);
    assertThat(SpringAiLlmClient.cachedPromptTokens(usage)).isEqualTo(8_000);
  }

  @Test
  void anthropicCacheReadsAndWritesAreAddedBackToThePrompt() {
    // AnthropicChatModel: input_tokens excludes cache reads and cache writes.
    DefaultUsage usage = new DefaultUsage(2_000, 50, 2_050,
        mock(com.anthropic.models.messages.Usage.class), 8_000L, 500L);

    assertThat(SpringAiLlmClient.promptTokens(usage)).isEqualTo(10_500);
    assertThat(SpringAiLlmClient.cachedPromptTokens(usage)).isEqualTo(8_000);
  }

  @Test
  void aProviderThatSaysNothingAboutItsCacheReportsNoCachedCount() {
    DefaultUsage usage = new DefaultUsage(100, 5);

    assertThat(SpringAiLlmClient.promptTokens(usage)).isEqualTo(100);
    assertThat(SpringAiLlmClient.cachedPromptTokens(usage)).isNull();
  }
}
