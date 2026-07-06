package io.github.yourimartin.gatewai.domain.model;

/**
 * Result of creating an API client. The {@code rawKey} is the plaintext key,
 * returned exactly once at creation; only its hash is stored.
 *
 * @param client the persisted client (carries the hash, not the raw key)
 * @param rawKey the plaintext API key to hand to the caller, shown once
 */
public record CreatedApiClient(ApiClient client, String rawKey) {}
