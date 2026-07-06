package io.github.yourimartin.gatewai.infrastructure.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gatewai.cache")
class SemanticCacheProperties {

  private double similarityThreshold = 0.92;
  private int topK = 1;
  private long ttlMinutes;
  private boolean clientNamespacing = true;

  double getSimilarityThreshold() {
    return similarityThreshold;
  }

  void setSimilarityThreshold(double similarityThreshold) {
    this.similarityThreshold = similarityThreshold;
  }

  int getTopK() {
    return topK;
  }

  void setTopK(int topK) {
    this.topK = topK;
  }

  long getTtlMinutes() {
    return ttlMinutes;
  }

  void setTtlMinutes(long ttlMinutes) {
    this.ttlMinutes = ttlMinutes;
  }

  boolean isClientNamespacing() {
    return clientNamespacing;
  }

  void setClientNamespacing(boolean clientNamespacing) {
    this.clientNamespacing = clientNamespacing;
  }
}
