package io.github.yourimartin.gatewai.domain.model.client;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An API client and its key's hash.
 *
 * @param mode          what the gateway does with its requests (v4 D.1)
 * @param modeChangedAt when an admin last changed {@code mode}, null if never
 * @param modeChangedBy the id of the admin client that did, null if never
 */
public record ApiClient(
    UUID id,
    String name,
    String apiKeyHash,
    boolean enabled,
    Instant createdAt,
    boolean admin,
    ClientMode mode,
    Instant modeChangedAt,
    String modeChangedBy
) {

  public ApiClient {
    Objects.requireNonNull(mode, "mode");
  }

  /** A client whose mode was never changed: {@link ClientMode#ENFORCE}. */
  public ApiClient(UUID id, String name, String apiKeyHash, boolean enabled,
                   Instant createdAt, boolean admin) {
    this(id, name, apiKeyHash, enabled, createdAt, admin, ClientMode.ENFORCE, null, null);
  }

  /** This client, disabled. */
  public ApiClient revoked() {
    return new ApiClient(id, name, apiKeyHash, false, createdAt, admin, mode,
        modeChangedAt, modeChangedBy);
  }

  /** This client in {@code newMode}, changed by {@code adminId} at {@code at}. */
  public ApiClient withMode(ClientMode newMode, String adminId, Instant at) {
    return new ApiClient(id, name, apiKeyHash, enabled, createdAt, admin, newMode, at, adminId);
  }
}
