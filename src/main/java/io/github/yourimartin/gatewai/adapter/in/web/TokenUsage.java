package io.github.yourimartin.gatewai.adapter.in.web;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TokenUsage(
    int promptTokens,
    int completionTokens,
    int totalTokens
) {}
