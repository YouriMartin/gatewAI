package com.example.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class NativeRuntimeHintsTest {

  @Test
  void registersBindingHintsForWebDtos() {
    RuntimeHints hints = new RuntimeHints();

    new NativeRuntimeHints().registerHints(hints, getClass().getClassLoader());

    assertTrue(RuntimeHintsPredicates.reflection()
        .onType(ChatCompletionResponse.class).test(hints));
    assertTrue(RuntimeHintsPredicates.reflection()
        .onType(RoutingConfigView.class).test(hints));
    assertTrue(RuntimeHintsPredicates.reflection()
        .onType(CreatedClientView.class).test(hints));
  }
}
