package io.github.yourimartin.gatewai.domain.model.decision;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A SHA-256 over a sequence of fields, each length-prefixed (v4 A.3).
 *
 * <p>Shared by the hashes that identify a context rather than a text — the
 * cache scope (ADR 0014) and the conversation fingerprint (ADR 0015). Every
 * field carries its length, so no text can forge a boundary between two
 * fields, and an absent field is distinct from an empty one. Callers start with
 * a version tag of their own, so two hashes over the same fields never collide.
 *
 * <p>The encoding is fixed: changing it changes every hash already stored.
 */
public final class FieldDigest {

  private static final int ABSENT = -1;

  private final MessageDigest digest;

  private FieldDigest(String version) {
    try {
      this.digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is guaranteed by the JDK", e);
    }
    field(version);
  }

  /** Starts a digest whose first field is {@code version}. */
  public static FieldDigest begin(String version) {
    return new FieldDigest(version);
  }

  /** Adds one text field; null is recorded as absent. */
  public FieldDigest field(String value) {
    if (value == null) {
      return count(ABSENT);
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    count(bytes.length);
    digest.update(bytes);
    return this;
  }

  /** Adds a count — a list's size before its elements, or {@link #absent()}. */
  public FieldDigest count(int value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    return this;
  }

  /** Records an absent list, distinct from an empty one. */
  public FieldDigest absent() {
    return count(ABSENT);
  }

  /** The 64-character hex SHA-256. Ends the digest. */
  public String hex() {
    return HexFormat.of().formatHex(digest.digest());
  }
}
