package com.example.gatewai.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes API keys for storage and lookup. Shared contract between the ingress
 * filter (hashes incoming keys) and the admin service (hashes new keys) so both
 * sides agree. Only the hash is ever persisted — never the raw key.
 */
public final class ApiKeyHasher {

  private ApiKeyHasher() {
  }

  /** SHA-256 hex of the raw key (64 hex chars). */
  public static String hash(String rawKey) {
    MessageDigest digest = sha256();
    digest.update(rawKey.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is guaranteed by the JDK", e);
    }
  }
}
