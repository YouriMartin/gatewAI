package io.github.yourimartin.gatewai.adapter.in.web.admin;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.model.client.ApiClient;
import io.github.yourimartin.gatewai.domain.model.client.ClientMode;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Safe view of an API client — never exposes the key hash or raw key.
 *
 * @param mode          {@code ENFORCE} or {@code SHADOW} (v4 D.1)
 * @param modeChangedAt when an admin last changed it, null if never
 * @param modeChangedBy the id of the admin client that did, null if never
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ApiClientView(
    String id,
    String name,
    boolean enabled,
    boolean admin,
    Instant createdAt,
    ClientMode mode,
    Instant modeChangedAt,
    String modeChangedBy
) {

  static ApiClientView of(ApiClient client) {
    return new ApiClientView(
        client.id().toString(), client.name(),
        client.enabled(), client.admin(), client.createdAt(),
        client.mode(), client.modeChangedAt(), client.modeChangedBy());
  }
}
