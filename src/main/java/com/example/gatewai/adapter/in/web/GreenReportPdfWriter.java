package com.example.gatewai.adapter.in.web;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Map;

import com.example.gatewai.domain.model.GreenReport;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

/** Renders a {@link GreenReport} as a one-page PDF for CSR / CSRD reporting. */
final class GreenReportPdfWriter {

  private static final Font TITLE_FONT =
      new Font(Font.HELVETICA, 16, Font.BOLD);

  private GreenReportPdfWriter() {
  }

  static byte[] toPdf(GreenReport report) {
    Document document = new Document();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      PdfWriter.getInstance(document, out);
      document.open();
      document.add(new Paragraph("Green AI Proxy — Carbon report", TITLE_FONT));
      document.add(new Paragraph(
          "Period: " + report.from() + " to " + report.to()));
      document.add(new Paragraph(
          "Requests: " + report.totalRequests()));
      document.add(new Paragraph(String.format(Locale.US,
          "Cache hit rate: %.1f%%", report.cacheHitRate() * 100)));
      document.add(new Paragraph(String.format(Locale.US,
          "Cost incurred: %.4f EUR", report.totalCostEur())));
      document.add(new Paragraph(String.format(Locale.US,
          "Cost avoided: %.4f EUR", report.totalCostAvoidedEur())));
      document.add(new Paragraph(String.format(Locale.US,
          "Energy: %.4f kWh", report.totalEnergyKwh())));
      document.add(new Paragraph(String.format(Locale.US,
          "CO2 emitted: %.2f g", report.totalGramsCo2())));
      document.add(new Paragraph(String.format(Locale.US,
          "CO2 avoided: %.2f g", report.totalGramsCo2Avoided())));
      document.add(new Paragraph("Model mix:"));
      for (Map.Entry<String, Long> entry : report.modelMix().entrySet()) {
        document.add(new Paragraph(
            "  - " + entry.getKey() + ": " + entry.getValue()));
      }
      document.close();
    } catch (DocumentException e) {
      throw new IllegalStateException("Failed to render PDF report", e);
    }
    return out.toByteArray();
  }
}
