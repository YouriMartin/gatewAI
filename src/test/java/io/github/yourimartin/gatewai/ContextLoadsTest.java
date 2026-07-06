package io.github.yourimartin.gatewai;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.SubmitDeferredRequestUseCase;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Boots the full Spring context (Phase 7.3) so bean-wiring regressions fail the
 * build. Two startup bugs — the MCP {@code ToolCallbackProvider} cycle and the
 * missing {@code CarbonAwareZoneSelector} bean — only surfaced at container
 * runtime because the default test suite never refreshes the context. This
 * integration test does: reaching the assertions means the whole graph wired
 * with no cycle and no missing bean. It makes <b>no</b> provider call.
 *
 * <p>Needs Postgres + Ollama, so it is {@code @Tag("integration")} — run with
 * {@code ./mvnw -Pit test}; the default {@code ./mvnw test} skips it. The
 * Anthropic key is not required (the model bean is created without calling out).
 */
@SpringBootTest
@Tag("integration")
class ContextLoadsTest {

  @Autowired
  private ApplicationContext context;

  @Test
  void contextWiresKeyBeans() {
    // The chat path (DelegatingChatModel egress + advisor chain).
    assertNotNull(context.getBean(ChatCompletionUseCase.class));
    // The deferred-dispatch path (regressed on the missing zone-selector bean).
    assertNotNull(context.getBean(SubmitDeferredRequestUseCase.class));
    // The @Primary provider-aware egress.
    assertNotNull(context.getBean(ChatModel.class));
  }
}
