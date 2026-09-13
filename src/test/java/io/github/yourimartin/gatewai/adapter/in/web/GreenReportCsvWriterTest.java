package io.github.yourimartin.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.GreenReport;

import org.junit.jupiter.api.Test;

class GreenReportCsvWriterTest {

  private static GreenReport report() {
    return new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        3, 1, 0.017, 0.028, 0.003, 1.61, 1.84,
        Map.of("haiku", 1L, "sonnet", 2L), Map.of());
  }

  /** All-local default: nothing accounted, and the report must say so. */
  private static GreenReport allExcludedReport() {
    return new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        4, 1, 0.0, 0.0, 0.0, 0.0, 0.0,
        Map.of("qwen2.5:3b", 4L), Map.of("qwen2.5:3b", 3L));
  }

  @Test
  void producesCsrdStructuredCsvWithModelMix() {
    String csv = GreenReportCsvWriter.toCsv(report());

    assertTrue(csv.startsWith("section,metric,value,unit,reference\n"), csv);
    // ESRS-referenced sections with units
    assertTrue(csv.contains(
        "Energy consumption,Total energy consumed,0.003000,kWh,ESRS E1-5\n"), csv);
    assertTrue(csv.contains(",kg CO2e,ESRS E1-6\n"), csv);
    // Activity + efficiency
    assertTrue(csv.contains("Resource efficiency,Requests served,3,count,\n"), csv);
    assertTrue(csv.contains("Avoided emissions (non-inventory)"), csv);
    // Model mix rows
    assertTrue(csv.contains("Model mix,haiku,1,requests,\n"), csv);
    assertTrue(csv.contains("Model mix,sonnet,2,requests,\n"), csv);
  }

  @Test
  void fullyAccountedReportStatesItWithoutExclusionNoise() {
    String csv = GreenReportCsvWriter.toCsv(report());

    assertTrue(csv.contains("Report,Emissions scope,ALL_ACCOUNTED,,\n"), csv);
    assertTrue(csv.contains("GHG emissions,Inferences excluded from scope,0,count,\n"),
        csv);
    // No exclusion section, no qualified metric labels, no avoided-basis note.
    assertFalse(csv.contains("Scope exclusions"), csv);
    assertFalse(csv.contains("[excluded from scope"), csv);
    assertFalse(csv.contains("[basis excluded from scope"), csv);
    assertFalse(csv.contains("Avoided emissions basis"), csv);
  }

  @Test
  void allExcludedReportLabelsEveryCo2FigureRatherThanShowingABareZero() {
    String csv = GreenReportCsvWriter.toCsv(allExcludedReport());

    assertTrue(csv.contains("Report,Emissions scope,ALL_EXCLUDED,,\n"), csv);
    assertTrue(csv.contains("excluded from scope"), csv);
    assertTrue(csv.contains(
        "Scope exclusions,qwen2.5:3b,3,requests,excluded from scope\n"), csv);
    assertTrue(csv.contains("Model mix,qwen2.5:3b,4,requests,excluded from scope\n"),
        csv);
    assertTrue(csv.contains("GHG emissions,Inferences excluded from scope,3,count,\n"),
        csv);

    for (String row : co2Rows(csv)) {
      assertTrue(row.contains("excluded from scope"),
          "a CO2 figure was rendered as a bare zero: " + row);
    }
  }

  @Test
  void partiallyExcludedReportNamesHowManyInferencesAreMissing() {
    GreenReport mixed = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        4, 0, 0.03, 0.05, 0.002, 1.2, 2.4,
        Map.of("claude-opus-4-8", 2L, "qwen2.5:3b", 2L),
        Map.of("qwen2.5:3b", 2L));

    String csv = GreenReportCsvWriter.toCsv(mixed);

    assertTrue(csv.contains("Report,Emissions scope,PARTIALLY_EXCLUDED,,\n"), csv);
    assertTrue(csv.contains("excludes 2 inference(s) out of scope"), csv);
    assertTrue(csv.contains("GHG emissions,Inferences in scope,2,count,\n"), csv);
    // Avoided sits next to an excluded actual: the basis note is mandatory.
    assertTrue(csv.contains("Avoided emissions basis"), csv);
    assertTrue(csv.contains("not on the same basis"), csv);
  }

  /** Rows whose unit is a CO2 measure — the ones that must never read bare. */
  private static List<String> co2Rows(String csv) {
    return Arrays.stream(csv.split("\n"))
        .filter(row -> row.contains(",kg CO2e,") || row.contains(",t CO2e,")
            || row.contains(",g CO2e/request,"))
        .toList();
  }
}
