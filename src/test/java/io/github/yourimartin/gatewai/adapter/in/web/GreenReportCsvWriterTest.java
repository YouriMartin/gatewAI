package io.github.yourimartin.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.EmissionsBreakdown;
import io.github.yourimartin.gatewai.domain.model.GreenReport;
import io.github.yourimartin.gatewai.domain.model.GreenProvenance;

import org.junit.jupiter.api.Test;

class GreenReportCsvWriterTest {

  private static GreenReport report() {
    return new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        3, 1, 0.017, 0.028, 0.003, 1.61, 1.84,
        Map.of("haiku", 1L, "sonnet", 2L), Map.of(), EmissionsBreakdown.EMPTY);
  }

  /** All-local default: nothing accounted, and the report must say so. */
  private static GreenReport allExcludedReport() {
    return new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        4, 1, 0.0, 0.0, 0.0, 0.0, 0.0,
        Map.of("qwen2.5:3b", 4L), Map.of("qwen2.5:3b", 3L), EmissionsBreakdown.EMPTY);
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
        Map.of("qwen2.5:3b", 2L), EmissionsBreakdown.EMPTY);

    String csv = GreenReportCsvWriter.toCsv(mixed);

    assertTrue(csv.contains("Report,Emissions scope,PARTIALLY_EXCLUDED,,\n"), csv);
    assertTrue(csv.contains("excludes 2 inference(s) out of scope"), csv);
    assertTrue(csv.contains("GHG emissions,Inferences in scope,2,count,\n"), csv);
    // Avoided sits next to an excluded actual: the basis note is mandatory.
    assertTrue(csv.contains("Avoided emissions basis"), csv);
    assertTrue(csv.contains("not on the same basis"), csv);
  }

  @Test
  void statesTheGhgAccountingBasisSoNobodyReadsItAsMarketBased() {
    String csv = GreenReportCsvWriter.toCsv(report());

    assertTrue(csv.contains("GHG accounting basis"), csv);
    assertTrue(csv.contains("Location-based Scope 2 only"), csv);
    assertTrue(csv.contains("Market-based accounting"), csv);
  }

  @Test
  void breaksEmissionsDownByRegionAndProviderAndFlagsAssumedRegions() {
    GreenReport attributed = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        3, 0, 0.03, 0.0, 0.002, 3.5, 0.0,
        Map.of("claude-opus-4-8", 2L, "mistral-large", 1L), Map.of(),
        new EmissionsBreakdown(
            Map.of("US-MIDA-PJM", 3.0, "FR", 0.5),
            Map.of("anthropic", 3.0, "vllm", 0.5),
            List.of("US-MIDA-PJM")));

    String csv = GreenReportCsvWriter.toCsv(attributed);

    assertTrue(csv.contains(
        "GHG emissions by region,US-MIDA-PJM,0.003000,kg CO2e,\"region assumed, not known\"\n"),
        csv);
    assertTrue(csv.contains("GHG emissions by region,FR,0.000500,kg CO2e,\n"), csv);
    assertTrue(csv.contains("GHG emissions by provider,anthropic,0.003000,kg CO2e,\n"),
        csv);
    assertTrue(csv.contains("GHG emissions by provider,vllm,0.000500,kg CO2e,\n"), csv);
    // On its face, in the header, not only in a column note.
    assertTrue(csv.contains("Report,Assumed regions,"), csv);
    assertTrue(csv.contains("Region assumed, not known, for: US-MIDA-PJM"), csv);
  }

  @Test
  void aReportWithNoAssumedRegionSaysNothingAboutOne() {
    GreenReport known = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        1, 0, 0.01, 0.0, 0.001, 0.5, 0.0,
        Map.of("mistral-large", 1L), Map.of(),
        new EmissionsBreakdown(Map.of("FR", 0.5), Map.of("vllm", 0.5), List.of()));

    String csv = GreenReportCsvWriter.toCsv(known);

    assertFalse(csv.contains("Assumed regions"), csv);
    assertFalse(csv.contains("region assumed"), csv);
  }

  @Test
  void anUnattributedRowIsReportedAsSuchRatherThanDropped() {
    GreenReport local = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        1, 0, 0.0, 0.0, 0.0, 0.0, 0.0,
        Map.of("qwen2.5:3b", 1L), Map.of("qwen2.5:3b", 1L),
        new EmissionsBreakdown(Map.of(GreenProvenance.UNATTRIBUTED_ZONE, 0.0),
            Map.of("ollama", 0.0), List.of()));

    String csv = GreenReportCsvWriter.toCsv(local);

    // "known" would be a false claim about a row that had no region at all.
    assertTrue(csv.contains(
        "GHG emissions by region,unattributed,0.000000,kg CO2e,no region attributed\n"),
        csv);
    assertTrue(csv.contains("GHG emissions by provider,ollama,0.000000,kg CO2e,\n"), csv);
  }

  /** Rows whose unit is a CO2 measure — the ones that must never read bare. */
  private static List<String> co2Rows(String csv) {
    return Arrays.stream(csv.split("\n"))
        .filter(row -> row.contains(",kg CO2e,") || row.contains(",t CO2e,")
            || row.contains(",g CO2e/request,"))
        .toList();
  }
}
