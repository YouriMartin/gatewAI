package io.github.yourimartin.gatewai.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable fingerprint of the routing rules (v2 batch 2).
 *
 * <p>{@link RoutingConfig} is editable in production through
 * {@code PUT /v1/admin/routing}. Without a version stamped on each decision,
 * an explanation read tomorrow would silently describe tomorrow's rules. This
 * hash is what lets a stored decision be marked stale instead.
 *
 * <p>Covers everything that can change an outcome: strategy, both length
 * thresholds, the premium keywords, the similarity threshold, and every route
 * with its tier and examples. Order matters — reordering routes changes which
 * one wins a tie, so it is not normalized away.
 */
public final class RoutingConfigVersion {

  /** Hex characters kept. 16 is collision-safe here and stays readable. */
  private static final int LENGTH = 16;

  private RoutingConfigVersion() {
  }

  /** Returns the version of {@code config}, or {@code null} when it is null. */
  public static String of(RoutingConfig config) {
    if (config == null) {
      return null;
    }

    StringBuilder canonical = new StringBuilder()
        .append("strategy=").append(config.strategy())
        .append("|entry=").append(config.entryLengthThreshold())
        .append("|premium=").append(config.premiumLengthThreshold())
        .append("|similarity=").append(config.routeSimilarityThreshold())
        .append("|keywords=").append(String.join(",", config.premiumKeywords()));

    for (SemanticRoute route : config.routes()) {
      canonical.append("|route=").append(route.name())
          .append(':').append(route.tier());
      for (String example : route.examples()) {
        // Length-prefixed, so ["ab","c"] and ["a","bc"] cannot hash alike
        // whatever separator an example happens to contain.
        canonical.append(':').append(example.length())
            .append(':').append(example);
      }
    }

    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is guaranteed by the JDK", e);
    }
    String hex = HexFormat.of().formatHex(
        digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    return hex.substring(0, LENGTH);
  }
}
