package com.example.gatewai.adapter.in.web;

import java.time.Instant;
import java.util.Map;

import com.example.gatewai.domain.model.GreenReport;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** JSON view of a {@link GreenReport} (snake_case, CSRD-friendly). */
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
    Map<String, Long> modelMix
) {

  public GreenReportResponse {
    modelMix = modelMix == null ? Map.of() : Map.copyOf(modelMix);
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
        report.modelMix()
    );
  }
}
