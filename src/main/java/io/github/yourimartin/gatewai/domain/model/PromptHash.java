package io.github.yourimartin.gatewai.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of a prompt, so decisions can be grouped and compared without ever
 * storing what the user wrote (v2 batch 2).
 */
public final class PromptHash {

  private PromptHash() {
  }

  /** Returns the hex SHA-256 of {@code text}, or null when it is null. */
  public static String of(String text) {
    if (text == null) {
      return null;
    }
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is guaranteed by the JDK", e);
    }
    return HexFormat.of().formatHex(
        digest.digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
