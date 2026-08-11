package io.github.yourimartin.gatewai.domain.port.out;

/**
 * Turns text into a vector, for the one job that needs raw embeddings outside
 * the advisor chain: calibrating the cache on labelled {@code (query, entry)}
 * pairs (v2 batch 3).
 *
 * <p>The router's calibration needs no such port — it reads the per-route
 * similarities the classifier already computes and reports in its
 * justification, which keeps the calibration fitted on exactly the numbers the
 * router decides with.
 */
public interface TextEmbedder {

  float[] embed(String text);

  /** Identifier of the model behind those vectors, stamped on a calibration. */
  String modelId();
}
