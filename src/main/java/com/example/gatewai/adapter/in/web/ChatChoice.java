package com.example.gatewai.adapter.in.web;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatChoice(
    int index,
    ChatMessage message,
    String finishReason
) {}
