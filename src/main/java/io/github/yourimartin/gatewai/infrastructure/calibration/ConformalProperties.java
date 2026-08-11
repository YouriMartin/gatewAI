package io.github.yourimartin.gatewai.infrastructure.calibration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the labelled cases a calibration is fitted on come from (v2 batch 3).
 *
 * <p>Both are Spring resource locations, so an operator points them at their own
 * labelled traffic (`file:/etc/gatewai/routing-labels.jsonl`) without writing
 * code. The risk levels themselves are not here: they belong to the calibration
 * service, which is where they are read.
 */
@ConfigurationProperties(prefix = "gatewai.conformal")
class ConformalProperties {

  /** Labelled prompts: {@code {"prompt": …, "expectedTier": …}} per line. */
  private String routingCases = "classpath:/eval/routing-calibration.jsonl";

  /** Labelled pairs: {@code {"query": …, "entry": …, "judgment": …}} per line. */
  private String cacheCases = "classpath:/eval/cache-calibration.jsonl";

  String getRoutingCases() {
    return routingCases;
  }

  void setRoutingCases(String routingCases) {
    this.routingCases = routingCases;
  }

  String getCacheCases() {
    return cacheCases;
  }

  void setCacheCases(String cacheCases) {
    this.cacheCases = cacheCases;
  }
}
