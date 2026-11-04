package io.github.yourimartin.gatewai.domain.port.in;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.client.ApiClient;
import io.github.yourimartin.gatewai.domain.model.client.ClientMode;
import io.github.yourimartin.gatewai.domain.model.client.CreatedApiClient;

/** Admin operations on API clients / keys (Phase 5.1). */
public interface ManageApiClientsUseCase {

  /**
   * Creates a client with a freshly generated key.
   *
   * @param name  human-readable client name
   * @param admin whether the client may call admin endpoints
   * @return the created client plus its raw key (shown once)
   */
  CreatedApiClient create(String name, boolean admin);

  /** Lists all clients (hashes included, never raw keys). */
  List<ApiClient> list();

  /** Disables a client so its key no longer authenticates. */
  void revoke(UUID id);

  /**
   * Puts a client in {@code mode} (v4 D.1). Takes effect on its next request.
   *
   * @param adminId the id of the admin client making the change, recorded with it
   * @return the client as it now is; empty when no client has that id
   */
  Optional<ApiClient> changeMode(UUID id, ClientMode mode, String adminId);
}
