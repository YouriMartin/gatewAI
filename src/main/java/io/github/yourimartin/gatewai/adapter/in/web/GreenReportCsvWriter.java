package io.github.yourimartin.gatewai.adapter.in.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.EmissionsScope;
import io.github.yourimartin.gatewai.domain.model.EnergySource;
import io.github.yourimartin.gatewai.domain.model.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.GreenReport;

/**
 * Renders a {@link GreenReport} as a CSRD-oriented CSV, structured with reference
 * to <b>ESRS E1 — Climate Change</b>: each row is
 * {@code section,metric,value,unit,reference}, so it pivots cleanly in Excel and
 * mirrors the PDF export.
 *
 * <p>"Avoided" figures are labelled non-inventory (an efficiency indicator, not
 * deducted from the GHG inventory), consistent with the GHG Protocol.
 *
 * <p>Emissions excluded from scope (v3 lot C.1) are never rendered as a bare
 * zero: the emission metrics carry a scope suffix, the report header states what
 * the totals cover, and every excluded model gets its own row.
 *
 * <p>Since v3 lot C.5 the header also states the GHG accounting basis
 * (location-based Scope 2 only) and names any region that was <b>assumed</b> rather
 * than known, and two sections break the emissions down by region and by provider.
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
    row(csv, "Report", "GHG accounting basis", GreenReport.SCOPE_BASIS, "", "ESRS E1-6");
    row(csv, "Report", "Emissions scope", report.emissionsScope().name(), "", "");
    row(csv, "Report", "Emissions scope note", report.scopeNote(), "", "");
    if (report.breakdown().hasAssumedRegions()) {
      row(csv, "Report", "Assumed regions", report.assumedRegionNote(), "", "");
    }

    // Energy consumption (ESRS E1-5)
    row(csv, "Energy consumption", "Total energy consumed",
        num(report.totalEnergyKwh(), 6), "kWh", "ESRS E1-5");
    row(csv, "Energy consumption", "Total energy consumed",
        num(report.totalEnergyKwh() / 1000.0, 9), "MWh", "ESRS E1-5");

    // GHG emissions (ESRS E1-6, estimated, location-based)
    double perRequest = report.totalRequests() == 0 ? 0.0
        : report.totalGramsCo2() / report.totalRequests();
    String scope = scopeSuffix(report);
    row(csv, "GHG emissions", "GHG emissions (location-based)" + scope,
        num(report.totalGramsCo2() / 1000.0, 6), "kg CO2e", "ESRS E1-6");
    row(csv, "GHG emissions", "GHG emissions (location-based)" + scope,
        num(report.totalGramsCo2() / 1_000_000.0, 9), "t CO2e", "ESRS E1-6");
    row(csv, "GHG emissions", "Emissions intensity" + scope,
        num(perRequest, 4), "g CO2e/request", "ESRS E1-6");
    row(csv, "GHG emissions", "Inferences in scope",
        String.valueOf(report.accountedRequests()), "count", "");
    row(csv, "GHG emissions", "Inferences excluded from scope",
        String.valueOf(report.excludedRequests()), "count", "");

    // Resource efficiency (supplementary, non-inventory)
    row(csv, "Resource efficiency", "Requests served",
        String.valueOf(report.totalRequests()), "count", "");
    row(csv, "Resource efficiency", "Cache hits",
        String.valueOf(report.cacheHits()), "count", "");
    row(csv, "Resource efficiency", "Cache hit rate",
        num(report.cacheHitRate() * 100.0, 2), "%", "");
    row(csv, "Resource efficiency",
        "Avoided emissions (non-inventory)" + avoidedScopeSuffix(report),
        num(report.totalGramsCo2Avoided() / 1000.0, 6), "kg CO2e", "");
    row(csv, "Resource efficiency", "Cost incurred",
        num(report.totalCostEur(), 6), "EUR", "");
    row(csv, "Resource efficiency", "Cost avoided (non-inventory)",
        num(report.totalCostAvoidedEur(), 6), "EUR", "");
    if (report.avoidedBasisDiffers()) {
      row(csv, "Resource efficiency", "Avoided emissions basis",
          GreenReport.AVOIDED_BASIS_NOTE, "", "");
    }

    // Scope exclusions — models whose energy is not accounted at all
    for (String model : report.excludedModels()) {
      row(csv, "Scope exclusions", model,
          String.valueOf(report.excludedModelMix().get(model)), "requests",
          EnergySource.NOT_ACCOUNTED.label());
    }

    // Emissions by region and by provider, from what each row stored about itself
    for (Map.Entry<String, Double> entry
        : report.breakdown().gramsCo2ByRegion().entrySet()) {
      row(csv, "GHG emissions by region", entry.getKey(),
          num(entry.getValue() / 1000.0, 6), "kg CO2e", regionNote(report, entry.getKey()));
    }
    for (Map.Entry<String, Double> entry
        : report.breakdown().gramsCo2ByProvider().entrySet()) {
      row(csv, "GHG emissions by provider", entry.getKey(),
          num(entry.getValue() / 1000.0, 6), "kg CO2e", "");
    }

    // Activity breakdown — model mix, each row labelled with its energy scope
    for (Map.Entry<String, Long> entry : report.modelMix().entrySet()) {
      String label = report.excludedModelMix().containsKey(entry.getKey())
          ? EnergySource.NOT_ACCOUNTED.label() : "";
      row(csv, "Model mix", entry.getKey(),
          String.valueOf(entry.getValue()), "requests", label);
    }

    return csv.toString();
  }

  /**
   * Attribution note for one zone row. The unattributed bucket says so rather than
   * borrowing the confidence of a real zone.
   */
  private static String regionNote(GreenReport report, String zone) {
    if (GreenProvenance.UNATTRIBUTED_ZONE.equals(zone)) {
      return "no region attributed";
    }
    return report.breakdown().assumedRegions().contains(zone)
        ? "region assumed, not known" : "";
  }

  /**
   * Suffix for the avoided figure, whose basis is the same registry: with part of
   * the activity unaccounted, the avoided number cannot be read as complete
   * either — so it carries the exclusion rather than standing alone.
   */
  private static String avoidedScopeSuffix(GreenReport report) {
    return report.emissionsScope() == EmissionsScope.ALL_ACCOUNTED
        || report.emissionsScope() == EmissionsScope.NO_ACTIVITY
        ? ""
        : " [basis " + EnergySource.NOT_ACCOUNTED.label() + " for "
            + report.excludedRequests() + " inference(s)]";
  }

  /**
   * Suffix appended to every emission metric so a zero is never bare: it names
   * the exclusion in the metric itself, where a spreadsheet reader will see it.
   */
  private static String scopeSuffix(GreenReport report) {
    EmissionsScope scope = report.emissionsScope();
    return switch (scope) {
      case NO_ACTIVITY, ALL_ACCOUNTED -> "";
      case ALL_EXCLUDED -> " [" + EnergySource.NOT_ACCOUNTED.label()
          + ": no accounted inference in this period]";
      case PARTIALLY_EXCLUDED -> " [excludes " + report.excludedRequests()
          + " inference(s) out of scope]";
    };
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
