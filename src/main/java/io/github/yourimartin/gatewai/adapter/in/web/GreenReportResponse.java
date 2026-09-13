package io.github.yourimartin.gatewai.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.GreenReport;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * JSON view of a {@link GreenReport} (snake_case, CSRD-friendly).
 *
 * <p>The {@code emissions_scope} block (v3 lot C.1) travels with the totals so no
 * consumer — the dashboard included — can read {@code total_grams_co2 = 0} as a
 * measured zero when part or all of the period is excluded from scope. The
 * {@code grams_co2_by_region} / {@code by_provider} split and {@code assumed_regions}
 * (v3 lot C.5) come from what each row stored, not from the current registry.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GreenReportResponse(
    Instant from,
    Instant to,
    long totalRequests,
    long cacheHits,
    double cacheHitRate,
    double totalCostEur,
    double totalCostAvoidedEur,
    double totalEnergyKwh,
    double totalGramsCo2,
    double totalGramsCo2Avoided,
    Map<String, Long> modelMix,
    String emissionsScope,
    String emissionsScopeNote,
    long accountedRequests,
    long excludedRequests,
    Map<String, Long> excludedModelMix,
    List<String> excludedModels,
    String avoidedBasisNote,
    String scopeBasis,
    Map<String, Double> gramsCo2ByRegion,
    Map<String, Double> gramsCo2ByProvider,
    List<String> assumedRegions,
    String assumedRegionNote
) {

  public GreenReportResponse {
    modelMix = modelMix == null ? Map.of() : Map.copyOf(modelMix);
    excludedModelMix =
        excludedModelMix == null ? Map.of() : Map.copyOf(excludedModelMix);
    excludedModels = excludedModels == null ? List.of() : List.copyOf(excludedModels);
    gramsCo2ByRegion =
        gramsCo2ByRegion == null ? Map.of() : Map.copyOf(gramsCo2ByRegion);
    gramsCo2ByProvider =
        gramsCo2ByProvider == null ? Map.of() : Map.copyOf(gramsCo2ByProvider);
    assumedRegions = assumedRegions == null ? List.of() : List.copyOf(assumedRegions);
  }

  static GreenReportResponse of(GreenReport report) {
    return new GreenReportResponse(
        report.from(),
        report.to(),
        report.totalRequests(),
        report.cacheHits(),
        report.cacheHitRate(),
        report.totalCostEur(),
        report.totalCostAvoidedEur(),
        report.totalEnergyKwh(),
        report.totalGramsCo2(),
        report.totalGramsCo2Avoided(),
        report.modelMix(),
        report.emissionsScope().name(),
        report.scopeNote(),
        report.accountedRequests(),
        report.excludedRequests(),
        report.excludedModelMix(),
        report.excludedModels(),
        report.avoidedBasisDiffers() ? GreenReport.AVOIDED_BASIS_NOTE : null,
        GreenReport.SCOPE_BASIS,
        report.breakdown().gramsCo2ByRegion(),
        report.breakdown().gramsCo2ByProvider(),
        report.breakdown().assumedRegions(),
        report.breakdown().hasAssumedRegions() ? report.assumedRegionNote() : null
    );
  }
}
