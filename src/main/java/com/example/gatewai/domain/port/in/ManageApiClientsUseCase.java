package com.example.gatewai.domain.port.in;

import java.util.List;
import java.util.UUID;

import com.example.gatewai.domain.model.ApiClient;
import com.example.gatewai.domain.model.CreatedApiClient;

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
}
