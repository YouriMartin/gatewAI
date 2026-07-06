package io.github.yourimartin.gatewai.adapter.in.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.GreenReport;

/**
 * Renders a {@link GreenReport} as a CSRD-oriented CSV, structured with reference
 * to <b>ESRS E1 — Climate Change</b>: each row is
 * {@code section,metric,value,unit,reference}, so it pivots cleanly in Excel and
 * mirrors the PDF export.
 *
 * <p>"Avoided" figures are labelled non-inventory (an efficiency indicator, not
 * deducted from the GHG inventory), consistent with the GHG Protocol.
 */
final class GreenReportCsvWriter {

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  private GreenReportCsvWriter() {
  }

  static String toCsv(GreenReport report) {
    StringBuilder csv = new StringBuilder("section,metric,value,unit,reference\n");

    row(csv, "Report", "Reporting period start", report.from().toString(), "", "");
    row(csv, "Report", "Reporting period end", report.to().toString(), "", "");
    row(csv, "Report", "Report generated", STAMP.format(Instant.now()), "", "");
    row(csv, "Report", "Basis of preparation",
        "Estimated; location-based; GWP-100; not externally assured", "", "");

    // Energy consumption (ESRS E1-5)
    row(csv, "Energy consumption", "Total energy consumed",
        num(report.totalEnergyKwh(), 6), "kWh", "ESRS E1-5");
    row(csv, "Energy consumption", "Total energy consumed",
        num(report.totalEnergyKwh() / 1000.0, 9), "MWh", "ESRS E1-5");

    // GHG emissions (ESRS E1-6, estimated, location-based)
    double perRequest = report.totalRequests() == 0 ? 0.0
        : report.totalGramsCo2() / report.totalRequests();
    row(csv, "GHG emissions", "GHG emissions (location-based)",
        num(report.totalGramsCo2() / 1000.0, 6), "kg CO2e", "ESRS E1-6");
    row(csv, "GHG emissions", "GHG emissions (location-based)",
        num(report.totalGramsCo2() / 1_000_000.0, 9), "t CO2e", "ESRS E1-6");
    row(csv, "GHG emissions", "Emissions intensity",
        num(perRequest, 4), "g CO2e/request", "ESRS E1-6");

    // Resource efficiency (supplementary, non-inventory)
    row(csv, "Resource efficiency", "Requests served",
        String.valueOf(report.totalRequests()), "count", "");
    row(csv, "Resource efficiency", "Cache hits",
        String.valueOf(report.cacheHits()), "count", "");
    row(csv, "Resource efficiency", "Cache hit rate",
        num(report.cacheHitRate() * 100.0, 2), "%", "");
    row(csv, "Resource efficiency", "Avoided emissions (non-inventory)",
        num(report.totalGramsCo2Avoided() / 1000.0, 6), "kg CO2e", "");
    row(csv, "Resource efficiency", "Cost incurred",
        num(report.totalCostEur(), 6), "EUR", "");
    row(csv, "Resource efficiency", "Cost avoided (non-inventory)",
        num(report.totalCostAvoidedEur(), 6), "EUR", "");

    // Activity breakdown — model mix
    for (Map.Entry<String, Long> entry : report.modelMix().entrySet()) {
      row(csv, "Model mix", entry.getKey(),
          String.valueOf(entry.getValue()), "requests", "");
    }

    return csv.toString();
  }

  private static void row(StringBuilder csv, String section, String metric,
                          String value, String unit, String reference) {
    csv.append(esc(section)).append(',')
        .append(esc(metric)).append(',')
        .append(esc(value)).append(',')
        .append(esc(unit)).append(',')
        .append(esc(reference)).append('\n');
  }

  private static String num(double value, int decimals) {
    return String.format(Locale.US, "%." + decimals + "f", value);
  }

  /** Minimal RFC 4180 escaping: quote fields containing comma, quote or newline. */
  private static String esc(String field) {
    if (field.indexOf(',') < 0 && field.indexOf('"') < 0
        && field.indexOf('\n') < 0) {
      return field;
    }
    return '"' + field.replace("\"", "\"\"") + '"';
  }
}
