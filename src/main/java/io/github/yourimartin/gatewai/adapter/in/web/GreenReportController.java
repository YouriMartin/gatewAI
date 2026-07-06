package io.github.yourimartin.gatewai.adapter.in.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import io.github.yourimartin.gatewai.domain.model.GreenReport;
import io.github.yourimartin.gatewai.domain.port.in.GenerateGreenReportUseCase;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Green reporting API (Phase 4.5). Aggregates cost/CO2 savings, cache hit rate
 * and model mix over a date range, with JSON / CSV / PDF output for CSR teams.
 */
@RestController
class GreenReportController {

  private static final MediaType TEXT_CSV = MediaType.parseMediaType("text/csv");

  private final GenerateGreenReportUseCase useCase;

  GreenReportController(GenerateGreenReportUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping("/v1/reports/green")
  ResponseEntity<?> report(@RequestParam String from,
                           @RequestParam String to,
                           @RequestParam(defaultValue = "json") String format) {
    Instant fromInstant;
    Instant toInstant;
    try {
      fromInstant = Instant.parse(from);
      toInstant = Instant.parse(to);
    } catch (DateTimeParseException e) {
      return ResponseEntity.badRequest().build();
    }

    GreenReport report = useCase.generate(fromInstant, toInstant);

    return switch (format.toLowerCase(Locale.ROOT)) {
      case "csv" -> ResponseEntity.ok()
          .contentType(TEXT_CSV)
          .header(HttpHeaders.CONTENT_DISPOSITION,
              "attachment; filename=\"green-report.csv\"")
          .body(GreenReportCsvWriter.toCsv(report));
      case "pdf" -> ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_PDF)
          .header(HttpHeaders.CONTENT_DISPOSITION,
              "attachment; filename=\"green-report.pdf\"")
          .body(GreenReportPdfWriter.toPdf(report));
      default -> ResponseEntity.ok(GreenReportResponse.of(report));
    };
  }

  @GetMapping("/v1/reports/green/series")
  ResponseEntity<?> series(@RequestParam String from,
                           @RequestParam String to) {
    Instant fromInstant;
    Instant toInstant;
    try {
      fromInstant = Instant.parse(from);
      toInstant = Instant.parse(to);
    } catch (DateTimeParseException e) {
      return ResponseEntity.badRequest().build();
    }
    try {
      List<GreenReportResponse> points = useCase.daily(fromInstant, toInstant)
          .stream().map(GreenReportResponse::of).toList();
      return ResponseEntity.ok(points);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    }
  }
}
