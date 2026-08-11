package io.github.yourimartin.gatewai.domain.model;

/**
 * Cosine similarity between two embeddings.
 *
 * <p>The same quantity throughout the gateway: what pgvector reports as
 * {@code 1 - (a <=> b)} for the cache, what the router computes against route
 * examples, and what a calibration's non-conformity scores are built from. One
 * implementation, so those three cannot drift apart.
 */
public final class Similarity {

  private Similarity() {
  }

  /** Returns the cosine of the angle between {@code a} and {@code b}, or 0. */
  public static double cosine(float[] a, float[] b) {
    if (a == null || b == null) {
      return 0;
    }
    double dot = 0;
    double normA = 0;
    double normB = 0;
    int length = Math.min(a.length, b.length);
    for (int i = 0; i < length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    if (normA == 0 || normB == 0) {
      return 0;
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }
}
