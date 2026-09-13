package io.github.yourimartin.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.EmissionsBreakdown;
import io.github.yourimartin.gatewai.domain.model.GreenReport;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import org.junit.jupiter.api.Test;

class GreenReportPdfWriterTest {

  @Test
  void producesNonEmptyPdfDocument() {
    GreenReport report = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        3, 1, 0.017, 0.028, 0.003, 1.61, 1.84,
        Map.of("haiku", 1L, "sonnet", 2L), Map.of(), EmissionsBreakdown.EMPTY);

    byte[] pdf = GreenReportPdfWriter.toPdf(report);

    assertTrue(pdf.length > 0);
    assertEquals("%PDF",
        new String(pdf, 0, 4, StandardCharsets.US_ASCII));
  }

  @Test
  void allExcludedReportSaysExcludedFromScopeInsteadOfShowingABareZero()
      throws IOException {
    GreenReport report = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        4, 1, 0.0, 0.0, 0.0, 0.0, 0.0,
        Map.of("qwen2.5:3b", 4L), Map.of("qwen2.5:3b", 3L), EmissionsBreakdown.EMPTY);

    String text = text(GreenReportPdfWriter.toPdf(report));

    // The scope boundary is stated, and the zero is qualified where it is shown.
    assertTrue(text.contains("EXCLUDED FROM SCOPE"), text);
    assertTrue(text.contains("No emissions accounted"), text);
    assertTrue(text.contains("GHG emissions (location-based) — excluded from scope"),
        text);
    // The model mix names the accounting status of each model.
    assertTrue(text.contains("Energy accounting"), text);
    assertTrue(text.contains("excluded from scope"), text);
    assertTrue(text.contains("Inferences excluded from scope"), text);
  }

  @Test
  void mixedReportFootnotesTheAvoidedFigureAgainstTheExcludedActual()
      throws IOException {
    GreenReport report = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        4, 0, 0.03, 0.05, 0.002, 1.2, 2.4,
        Map.of("claude-opus-4-8", 2L, "qwen2.5:3b", 2L),
        Map.of("qwen2.5:3b", 2L), EmissionsBreakdown.EMPTY);

    String text = text(GreenReportPdfWriter.toPdf(report));

    assertTrue(text.contains("not on the same basis"), text);
    assertTrue(text.contains("excludes 2 inference(s) out of scope"), text);
  }

  @Test
  void fullyAccountedReportCarriesNoExclusionWording() throws IOException {
    GreenReport report = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        2, 0, 0.03, 0.05, 0.002, 1.2, 2.4,
        Map.of("claude-opus-4-8", 2L), Map.of(), EmissionsBreakdown.EMPTY);

    String text = text(GreenReportPdfWriter.toPdf(report));

    assertTrue(text.contains("All inference in this period was served by "
        + "energy-accounted models"), text);
    assertFalse(text.contains("not on the same basis"), text);
  }

  @Test
  void attributesEmissionsByRegionAndProviderAndNamesAssumedRegions()
      throws IOException {
    GreenReport report = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        3, 0, 0.03, 0.0, 0.002, 3.5, 0.0,
        Map.of("claude-opus-4-8", 2L, "mistral-large", 1L), Map.of(),
        new EmissionsBreakdown(
            Map.of("US-MIDA-PJM", 3.0, "FR", 0.5),
            Map.of("anthropic", 3.0, "vllm", 0.5),
            List.of("US-MIDA-PJM")));

    String text = text(GreenReportPdfWriter.toPdf(report));

    assertTrue(text.contains("Emissions attribution"), text);
    assertTrue(text.contains("US-MIDA-PJM"), text);
    assertTrue(text.contains("assumed, not known"), text);
    assertTrue(text.contains("anthropic"), text);
    assertTrue(text.contains("vllm"), text);
    // The accounting basis is on the document, not implied.
    assertTrue(text.contains("Location-based Scope 2 only"), text);
    assertTrue(text.contains("Region assumed, not known, for: US-MIDA-PJM"), text);
  }

  @Test
  void anUnattributedZoneIsNotPresentedAsAKnownRegion() throws IOException {
    GreenReport report = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        1, 0, 0.0, 0.0, 0.0, 0.0, 0.0,
        Map.of("qwen2.5:3b", 1L), Map.of("qwen2.5:3b", 1L),
        new EmissionsBreakdown(Map.of("unattributed", 0.0), Map.of("ollama", 0.0),
            List.of()));

    String text = text(GreenReportPdfWriter.toPdf(report));

    assertTrue(text.contains("no region attributed"), text);
    assertFalse(text.contains("unattributed 0.000000 known"), text);
  }

  private static String text(byte[] pdf) throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      StringBuilder text = new StringBuilder();
      for (int page = 1; page <= reader.getNumberOfPages(); page++) {
        text.append(extractor.getTextFromPage(page)).append('\n');
      }
      return text.toString();
    } finally {
      reader.close();
    }
  }
}
