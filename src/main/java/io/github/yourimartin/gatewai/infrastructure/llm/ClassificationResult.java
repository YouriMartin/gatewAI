package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.ModelTier;

/**
 * Structured output (Spring AI {@code entity}) returned by the LLM-based
 * complexity classifier. The model is instructed to emit JSON matching this
 * shape; Spring AI's {@code BeanOutputConverter} maps it back to this record.
 *
 * @param tier      the complexity tier the request was classified into
 * @param reasoning short justification, kept for logging/observability only
 */
record ClassificationResult(ModelTier tier, String reasoning) {}
