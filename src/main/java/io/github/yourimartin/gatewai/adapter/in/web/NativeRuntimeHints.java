package io.github.yourimartin.gatewai.adapter.in.web;

import java.util.List;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers Jackson binding reflection hints for the web DTOs so they
 * (de)serialize under GraalVM native image (Phase 6.3). Controller body types
 * are largely auto-registered by Spring MVC's AOT, but we declare them
 * explicitly so the contract is verifiable and robust.
 */
class NativeRuntimeHints implements RuntimeHintsRegistrar {

  private static final List<Class<?>> BOUND_DTOS = List.of(
      ChatCompletionRequest.class,
      ChatCompletionResponse.class,
      ChatCompletionChunk.class,
      ChunkChoice.class,
      ChatMessage.class,
      ChatChoice.class,
      TokenUsage.class,
      DeferredJobResponse.class,
      GreenReportResponse.class,
      ApiClientView.class,
      CreatedClientView.class,
      CreateClientRequest.class,
      RoutingConfigView.class,
      RoutingConfigView.RouteView.class);

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    BindingReflectionHintsRegistrar binding = new BindingReflectionHintsRegistrar();
    for (Class<?> dto : BOUND_DTOS) {
      binding.registerReflectionHints(hints.reflection(), dto);
    }
  }
}
