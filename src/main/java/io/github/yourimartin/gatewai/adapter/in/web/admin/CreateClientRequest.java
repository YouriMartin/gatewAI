package io.github.yourimartin.gatewai.adapter.in.web.admin;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Body for creating an API client.
 *
 * @param name  the client's display name
 * @param admin whether it gets {@code ROLE_ADMIN}. Optional: absent or null means
 *              {@code false}. It is boxed because Jackson 3 refuses a missing
 *              primitive ({@code FAIL_ON_NULL_FOR_PRIMITIVES} is on by default), which
 *              turned {@code {"name": "my-app"}} into a 400; the compact constructor
 *              keeps the accessor non-null.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateClientRequest(String name, Boolean admin) {

  public CreateClientRequest {
    admin = Boolean.TRUE.equals(admin);
  }
}
