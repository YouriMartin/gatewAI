package io.github.yourimartin.gatewai.domain.port.out;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.LabelledCase;

/**
 * Supplies the labelled cases a calibration is fitted on (v2 batch 3).
 *
 * <p>The gateway ships with a default set so that "calibrate" is a working
 * button on a fresh install rather than a feature waiting for someone else's
 * data. Operators with their own labelled traffic replace the implementation —
 * which is the only reason this is a port and not a file read inline.
 */
public interface LabelledCaseSource {

  List<LabelledCase.Routing> routingCases();

  List<LabelledCase.CachePair> cachePairs();

  /** Where these labels came from, for the record. */
  String description();
}
