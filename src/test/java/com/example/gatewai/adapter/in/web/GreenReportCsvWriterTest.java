package com.example.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import com.example.gatewai.domain.model.GreenReport;

import org.junit.jupiter.api.Test;

class GreenReportCsvWriterTest {

  private static GreenReport report() {
    return new GreenReport(
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:00Z"),
        3, 1, 0.017, 0.028, 0.003, 1.61, 1.84,
        Map.of("haiku", 1L, "sonnet", 2L));
  }

  @Test
  void producesKeyValueCsvWithModelMix() {
    String csv = GreenReportCsvWriter.toCsv(report());

    assertTrue(csv.startsWith("metric,value\n"), csv);
    assertTrue(csv.contains("total_requests,3\n"), csv);
    assertTrue(csv.contains("cache_hits,1\n"), csv);
    assertTrue(csv.contains("total_cost_avoided_eur,0.028000\n"), csv);
    assertTrue(csv.contains("model:haiku,1\n"), csv);
    assertTrue(csv.contains("model:sonnet,2\n"), csv);
  }
}
