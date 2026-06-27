package com.example.gatewai.adapter.in.web;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Body for creating an API client. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateClientRequest(String name, boolean admin) {}
