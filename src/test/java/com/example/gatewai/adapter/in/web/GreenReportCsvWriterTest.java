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
}
