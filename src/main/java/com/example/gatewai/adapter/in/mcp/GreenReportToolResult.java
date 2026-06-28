package com.example.gatewai.adapter.in.mcp;

import java.util.Map;

import com.example.gatewai.domain.model.GreenReport;

/**
 * MCP-facing shape of an aggregated {@link GreenReport}. Kept separate from the
 * domain record so the tool's JSON schema is owned by the adapter and exposes
 * the derived {@code cacheHitRate} explicitly.
 *
 * @param from                 inclusive start of the range (ISO-8601)
 * @param to                   inclusive end of the range (ISO-8601)
 * @param totalRequests        number of requests served in the range
 * @param cacheHits            requests answered from the semantic cache
 * @param cacheHitRate         share of requests served from cache, in [0, 1]
 * @param totalCostEur         total monetary cost actually incurred
 * @param totalCostAvoidedEur  money saved vs a premium-default baseline
 * @param totalEnergyKwh       total estimated energy consumed
 * @param totalGramsCo2        total estimated emissions actually produced
 * @param totalGramsCo2Avoided total emissions avoided vs a premium baseline
 * @param modelMix             model id → number of requests it served
 */
record GreenReportToolResult(
    String from,
    String to,
    long totalRequests,
    long cacheHits,
    double cacheHitRate,
    double totalCostEur,
    double totalCostAvoidedEur,
    double totalEnergyKwh,
    double totalGramsCo2,
    double totalGramsCo2Avoided,
    Map<String, Long> modelMix) {

  static GreenReportToolResult from(GreenReport report) {
    return new GreenReportToolResult(
        report.from().toString(),
        report.to().toString(),
        report.totalRequests(),
        report.cacheHits(),
        report.cacheHitRate(),
        report.totalCostEur(),
        report.totalCostAvoidedEur(),
        report.totalEnergyKwh(),
        report.totalGramsCo2(),
        report.totalGramsCo2Avoided(),
        report.modelMix());
  }
}
