package com.example.gatewai.adapter.in.web;

import com.example.gatewai.domain.model.CreatedApiClient;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Create response: the client view plus the raw key, returned exactly once. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreatedClientView(ApiClientView client, String apiKey) {

  static CreatedClientView of(CreatedApiClient created) {
    return new CreatedClientView(
        ApiClientView.of(created.client()), created.rawKey());
  }
}
