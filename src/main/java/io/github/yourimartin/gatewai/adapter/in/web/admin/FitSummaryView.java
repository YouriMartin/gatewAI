package io.github.yourimartin.gatewai.adapter.in.web.admin;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import io.github.yourimartin.gatewai.domain.model.calibration.FitSummary;

/**
 * What a calibration was fitted on, as the admin API reports it (v4 D.3,
 * ADR 0019). One shape for both sources: the fields of the other one are null.
 *
 * @param type                      {@code labelledSet} or {@code shadow}
 * @param source                    labelled set: where the cases were read from
 * @param read                      labelled set: cases read
 * @param fitted                    labelled set: cases the quantile was fitted on
 * @param exclusions                shadow: labels read, and why each one left out was
 * @param yes                       shadow: counted pairs the judge found servable
 * @param no                        shadow: counted pairs it did not, which q-hat is
 *                                  fitted on
 * @param labelsFrom                shadow: oldest counted label
 * @param labelsTo                  shadow: newest counted label
 * @param refusedServableShare      shadow: of the counted YES pairs, the share the
 *                                  threshold refuses
 * @param ambiguousBecomingServable shadow: decisions ambiguous at the floor that the
 *                                  threshold serves, with no label for them
 * @param modelledRateP90           shadow: with probability 0.9 the fit admits at
 *                                  most this share of the judge's NO pairs
 * @param modelledRateP90Basis      how that figure was obtained — never measured
 * @param minPairs                  shadow: the judged-pair minimum it was held to
 * @param minNegatives              shadow: the NO minimum it was held to
 * @param perClient                 shadow: the counts, client by client
 */
public record FitSummaryView(
    String type,
    String source,
    Integer read,
    Integer fitted,
    FitSummary.Exclusions exclusions,
    Integer yes,
    Integer no,
    Instant labelsFrom,
    Instant labelsTo,
    Double refusedServableShare,
    Long ambiguousBecomingServable,
    Double modelledRateP90,
    String modelledRateP90Basis,
    Integer minPairs,
    Integer minNegatives,
    Map<String, FitSummary.ClientCounts> perClient
) {

  public FitSummaryView {
    // Sorted, so clients read in a stable order; unmodifiable, so the view is.
    perClient = perClient == null ? null
        : Collections.unmodifiableSortedMap(new TreeMap<>(perClient));
  }

  static final String P90_BASIS =
      "modelled from the number of NO pairs (Beta law of split conformal), not measured";

  static FitSummaryView of(FitSummary summary) {
    return switch (summary) {
      case null -> null;
      case FitSummary.LabelledSet labelled -> new FitSummaryView("labelledSet",
          labelled.source(), labelled.read(), labelled.fitted(), null, null, null, null,
          null, null, null, null, null, null, null, null);
      case FitSummary.Shadow shadow -> new FitSummaryView("shadow", null, null, null,
          shadow.exclusions(), shadow.yes(), shadow.no(), shadow.labelsFrom(),
          shadow.labelsTo(), shadow.refusedServableShare(),
          shadow.ambiguousBecomingServable(), shadow.modelledRateP90(), P90_BASIS,
          shadow.minPairs(), shadow.minNegatives(), shadow.perClient());
    };
  }
}
