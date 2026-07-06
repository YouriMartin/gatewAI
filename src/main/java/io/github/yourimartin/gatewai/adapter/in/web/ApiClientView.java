package io.github.yourimartin.gatewai.adapter.in.web;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.model.ApiClient;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Safe view of an API client — never exposes the key hash or raw key. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ApiClientView(
    String id,
    String name,
    boolean enabled,
    boolean admin,
    Instant createdAt
) {

  static ApiClientView of(ApiClient client) {
    return new ApiClientView(
        client.id().toString(), client.name(),
        client.enabled(), client.admin(), client.createdAt());
  }
}
