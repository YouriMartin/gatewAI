package io.github.yourimartin.gatewai.adapter.in.mcp;

import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.GreenReport;

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
 * @param emissionsScope       how much of the period the CO2 totals cover
 *                             (v3 lot C.1): {@code NO_ACTIVITY},
 *                             {@code ALL_ACCOUNTED}, {@code PARTIALLY_EXCLUDED}
 *                             or {@code ALL_EXCLUDED}
 * @param emissionsScopeNote   one sentence stating what the totals cover, so an
 *                             assistant never reports a zero as measured
 * @param excludedRequests     inferences served by models excluded from scope
 * @param excludedModels       the model ids behind those requests
 * @param scopeBasis           what the figures are in GHG-Protocol terms
 *                             (location-based Scope 2 only), so an assistant cannot
 *                             present them as something broader
 * @param gramsCo2ByRegion     grid zone → gCO2 (v3 lot C.5)
 * @param gramsCo2ByProvider   provider instance → gCO2
 * @param assumedRegions       zones whose region was declared, not known
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
    Map<String, Long> modelMix,
    String emissionsScope,
    String emissionsScopeNote,
    long excludedRequests,
    List<String> excludedModels,
    String scopeBasis,
    Map<String, Double> gramsCo2ByRegion,
    Map<String, Double> gramsCo2ByProvider,
    List<String> assumedRegions) {

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
        report.modelMix(),
        report.emissionsScope().name(),
        report.scopeNote(),
        report.excludedRequests(),
        report.excludedModels(),
        GreenReport.SCOPE_BASIS,
        report.breakdown().gramsCo2ByRegion(),
        report.breakdown().gramsCo2ByProvider(),
        report.breakdown().assumedRegions());
  }
}
