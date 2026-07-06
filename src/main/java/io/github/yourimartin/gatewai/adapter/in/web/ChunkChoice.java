package io.github.yourimartin.gatewai.adapter.in.web;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** One choice of a streaming chunk: an incremental {@code delta} + finish reason. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChunkChoice(
    int index,
    ChatMessage delta,
    String finishReason
) {}
