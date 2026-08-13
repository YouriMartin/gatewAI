package io.github.yourimartin.gatewai.domain.model;

/**
 * What one segment of the prompt contributed to the routing similarity
 * (v2 batch 7).
 *
 * @param segment      the text this describes
 * @param contribution {@code similarity(prompt) − similarity(prompt without this
 *                     segment)}. Positive means removing it moved the prompt
 *                     <em>away</em> from the matched route, so it pulled the
 *                     decision there. <b>Negative is meaningful</b>, not noise:
 *                     the segment was pulling the other way and the rest of the
 *                     prompt won anyway
 * @param share        the segment's part of the total positive contribution,
 *                     0 when nothing contributed positively. A readable "60 % of
 *                     what sent this request to premium", with the caveat that
 *                     it is a normalization, not a probability
 * @param rank         1-based position by contribution, strongest first
 */
public record SegmentAttribution(String segment, double contribution,
                                 double share, int rank) {
}
