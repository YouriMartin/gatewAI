package io.github.yourimartin.gatewai.adapter.in.web.admin;

/**
 * What a recalibration fits, and at which risk levels (v2 batch 3; shadow source
 * since v4 D.3, ADR 0019). Every field is optional.
 *
 * @param routingAlpha share of prompts whose correct route may fall outside the
 *                     prediction set; labelled-set fits only
 * @param cacheAlpha   share of non-servable pairs that may be served anyway
 * @param source       {@code labelled-set} (default): both targets, from the
 *                     hand-labelled cases; {@code shadow}: the cache only, from the
 *                     judge's verdicts on shadow would-hits
 * @param client       shadow only: the client to fit; absent pools every shadow
 *                     client into the global row
 * @param judgeModel   shadow only: the judge whose verdicts count; absent uses
 *                     {@code gatewai.shadow.judge.model}
 */
public record RecalibrateRequest(Double routingAlpha, Double cacheAlpha, String source,
                                 String client, String judgeModel) {

  /** The source names the API accepts. */
  static final String LABELLED_SET = "labelled-set";
  static final String SHADOW = "shadow";

  /** No body: a labelled-set fit at the configured defaults. */
  static RecalibrateRequest defaults() {
    return new RecalibrateRequest(null, null, null, null, null);
  }

  boolean isShadow() {
    return SHADOW.equals(source);
  }

  /**
   * What is wrong with the request's shape, or null. Checked before any fit, so a
   * field that would be ignored is refused instead.
   */
  String problem() {
    if (source != null && !LABELLED_SET.equals(source) && !SHADOW.equals(source)) {
      return "source must be '" + LABELLED_SET + "' or '" + SHADOW + "', was '" + source + "'";
    }
    if (isShadow() && routingAlpha != null) {
      return "a shadow fit calibrates the cache only: routingAlpha does not apply";
    }
    if (!isShadow() && (client != null || judgeModel != null)) {
      return "client and judgeModel apply to source=shadow only";
    }
    return null;
  }
}
