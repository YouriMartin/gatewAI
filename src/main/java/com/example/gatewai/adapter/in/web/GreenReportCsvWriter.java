package com.example.gatewai.adapter.in.web;

import java.util.Locale;
import java.util.Map;

import com.example.gatewai.domain.model.GreenReport;

/** Renders a {@link GreenReport} as a flat key/value CSV (opens in Excel). */
final class GreenReportCsvWriter {

  private GreenReportCsvWriter() {
  }

  static String toCsv(GreenReport report) {
    StringBuilder csv = new StringBuilder("metric,value\n");
    csv.append("period_from,").append(report.from()).append('\n');
    csv.append("period_to,").append(report.to()).append('\n');
    csv.append("total_requests,").append(report.totalRequests()).append('\n');
    csv.append("cache_hits,").append(report.cacheHits()).append('\n');
    csv.append("cache_hit_rate,").append(num(report.cacheHitRate())).append('\n');
    csv.append("total_cost_eur,").append(num(report.totalCostEur())).append('\n');
    csv.append("total_cost_avoided_eur,")
        .append(num(report.totalCostAvoidedEur())).append('\n');
    csv.append("total_energy_kwh,")
        .append(num(report.totalEnergyKwh())).append('\n');
    csv.append("total_grams_co2,")
        .append(num(report.totalGramsCo2())).append('\n');
    csv.append("total_grams_co2_avoided,")
        .append(num(report.totalGramsCo2Avoided())).append('\n');
    for (Map.Entry<String, Long> entry : report.modelMix().entrySet()) {
      csv.append("model:").append(entry.getKey())
          .append(',').append(entry.getValue()).append('\n');
    }
    return csv.toString();
  }

  private static String num(double value) {
    return String.format(Locale.US, "%.6f", value);
  }
}
