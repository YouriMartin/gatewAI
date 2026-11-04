package io.github.yourimartin.gatewai.adapter.in.web.admin;

import io.github.yourimartin.gatewai.domain.model.shadow.WilsonInterval;
import io.github.yourimartin.gatewai.domain.model.shadow.WouldHitCounts;
import io.github.yourimartin.gatewai.domain.model.shadow.WrongHitEstimate;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One client's estimated wrong-hit rate under one judge (v4 D.2). The rate, its
 * interval, its sample size and the judge label always travel together.
 *
 * @param judged         YES + NO: the sample size of the rate
 * @param wrongHitRate   NO / judged, null when nothing was judged
 * @param intervalLow    Wilson 95 % lower bound, null with the rate
 * @param intervalHigh   Wilson 95 % upper bound, null with the rate
 * @param confidence     the interval's confidence level
 * @param label          who judged — never a human — or why there is no rate
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WrongHitView(
    String clientId,
    String judgeModel,
    String rubricVersion,
    long wouldHits,
    Double meanAnswerSimilarity,
    long beyondWindow,
    long judged,
    long yes,
    long no,
    long unsure,
    long failed,
    Double wrongHitRate,
    Double intervalLow,
    Double intervalHigh,
    double confidence,
    String label
) {

  static WrongHitView of(WrongHitEstimate estimate) {
    WouldHitCounts counts = estimate.counts();
    WilsonInterval interval = estimate.interval();
    return new WrongHitView(counts.clientId(), counts.judgeModel(), counts.rubricVersion(),
        counts.wouldHits(), counts.meanSimilarity(), counts.beyondWindow(), estimate.judged(),
        counts.yes(), counts.no(), counts.unsure(), counts.failed(), estimate.rate(),
        interval == null ? null : interval.low(), interval == null ? null : interval.high(),
        0.95, estimate.label());
  }
}
