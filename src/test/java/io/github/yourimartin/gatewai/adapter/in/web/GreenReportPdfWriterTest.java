package io.github.yourimartin.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.GreenReport;

import org.junit.jupiter.api.Test;

class GreenReportPdfWriterTest {

  @Test
  void producesNonEmptyPdfDocument() {
    GreenReport report = new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        3, 1, 0.017, 0.028, 0.003, 1.61, 1.84,
        Map.of("haiku", 1L, "sonnet", 2L));

    byte[] pdf = GreenReportPdfWriter.toPdf(report);

    assertTrue(pdf.length > 0);
    assertEquals("%PDF",
        new String(pdf, 0, 4, StandardCharsets.US_ASCII));
  }
}
