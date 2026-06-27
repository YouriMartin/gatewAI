package com.example.gatewai.adapter.in.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/** Wires {@link NativeRuntimeHints} into the AOT/native build (Phase 6.3). */
@Configuration
@ImportRuntimeHints(NativeRuntimeHints.class)
class NativeHintsConfiguration {
}
