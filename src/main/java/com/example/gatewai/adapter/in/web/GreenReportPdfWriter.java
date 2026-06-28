package com.example.gatewai.adapter.in.web;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import com.example.gatewai.domain.model.GreenReport;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Renders a {@link GreenReport} as a sustainability-report-style PDF, structured
 * with reference to <b>ESRS E1 — Climate Change</b> (the EU CSRD climate
 * standard): a basis of preparation, energy consumption (E1-5) and estimated GHG
 * emissions (E1-6), plus supplementary resource-efficiency indicators.
 *
 * <p>Honest framing matters for CSRD: the figures are <em>estimates</em>, and
 * "avoided" emissions/cost are reported as a separate efficiency indicator —
 * <b>not</b> deducted from the GHG inventory (consistent with the GHG Protocol).
 */
final class GreenReportPdfWriter {

  private static final Color GREEN = new Color(27, 94, 32);
  private static final Color GREEN_HEADER = new Color(46, 125, 50);
  private static final Color GRAY = new Color(90, 90, 90);
  private static final Color ZEBRA = new Color(244, 248, 244);

  private static final Font TITLE_FONT = new Font(Font.HELVETICA, 18, Font.BOLD, GREEN);
  private static final Font SUBTITLE_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL, GRAY);
  private static final Font META_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, GRAY);
  private static final Font H2_FONT = new Font(Font.HELVETICA, 12, Font.BOLD, GREEN);
  private static final Font BODY_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.DARK_GRAY);
  private static final Font SMALL_FONT = new Font(Font.HELVETICA, 7, Font.ITALIC, GRAY);
  private static final Font TH_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
  private static final Font LABEL_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
  private static final Font VALUE_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK);

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  private GreenReportPdfWriter() {
  }

  static byte[] toPdf(GreenReport report) {
    Document document = new Document(PageSize.A4, 48, 48, 54, 48);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      PdfWriter.getInstance(document, out);
      document.open();

      header(document, report);
      basisOfPreparation(document);
      energySection(document, report);
      emissionsSection(document, report);
      efficiencySection(document, report);
      modelMixSection(document, report);
      footer(document);

      document.close();
    } catch (DocumentException e) {
      throw new IllegalStateException("Failed to render PDF report", e);
    }
    return out.toByteArray();
  }

  private static void header(Document doc, GreenReport report) throws DocumentException {
    Paragraph title = new Paragraph("AI Carbon Footprint Report", TITLE_FONT);
    doc.add(title);
    doc.add(new Paragraph(
        "Prepared with reference to ESRS E1 — Climate Change (CSRD)", SUBTITLE_FONT));
    doc.add(rule());

    Paragraph meta = new Paragraph();
    meta.setFont(META_FONT);
    meta.add("Reporting entity: ____________________________\n");
    meta.add("Reporting period: " + DATE.format(report.from())
        + " to " + DATE.format(report.to()) + "\n");
    meta.add("Report generated: " + STAMP.format(Instant.now()) + "\n");
    meta.add("Scope of report: greenhouse-gas emissions and energy from AI (LLM) "
        + "inference routed through the gateway.");
    meta.setSpacingBefore(8);
    meta.setSpacingAfter(6);
    doc.add(meta);
  }

  private static void basisOfPreparation(Document doc) throws DocumentException {
    heading(doc, "1. Basis of preparation");
    doc.add(bullet("Boundary: energy and emissions attributable to LLM inference "
        + "served through the gateway. Requests answered from the semantic cache "
        + "incur no inference and therefore no emissions."));
    doc.add(bullet("GHG classification: emissions are electricity-related. They fall "
        + "under Scope 2 when the models are self-hosted, or Scope 3 (purchased "
        + "services) when served via a third-party API — classify per your "
        + "deployment."));
    doc.add(bullet("Emission factor: location-based electricity-grid carbon intensity "
        + "(gCO2e/kWh). Greenhouse gases expressed as CO2-equivalent (GWP-100)."));
    doc.add(bullet("Estimation basis: energy = tokens x per-model energy intensity "
        + "(estimated coefficients); emissions = energy x grid intensity."));
    doc.add(bullet("Limitations: figures are estimates from indicative coefficients, "
        + "not externally assured. They are directional and intended to support, "
        + "not replace, an audited disclosure."));
  }

  private static void energySection(Document doc, GreenReport r) throws DocumentException {
    heading(doc, "2. Energy consumption (ESRS E1-5)");
    PdfPTable t = metricTable();
    metricRow(t, "Total energy consumed", fmt(r.totalEnergyKwh(), 4) + " kWh", false);
    metricRow(t, "Total energy consumed (equiv.)",
        fmt(r.totalEnergyKwh() / 1000.0, 6) + " MWh", true);
    doc.add(t);
  }

  private static void emissionsSection(Document doc, GreenReport r) throws DocumentException {
    heading(doc, "3. GHG emissions — estimated (ESRS E1-6)");
    double kg = r.totalGramsCo2() / 1000.0;
    double tonnes = r.totalGramsCo2() / 1_000_000.0;
    double perRequest = r.totalRequests() == 0 ? 0.0
        : r.totalGramsCo2() / r.totalRequests();
    PdfPTable t = metricTable();
    metricRow(t, "GHG emissions (location-based)", fmt(kg, 3) + " kg CO2e", false);
    metricRow(t, "GHG emissions (equiv.)", fmt(tonnes, 6) + " t CO2e", true);
    metricRow(t, "Emissions intensity", fmt(perRequest, 3) + " g CO2e / request", false);
    doc.add(t);
  }

  private static void efficiencySection(Document doc, GreenReport r) throws DocumentException {
    heading(doc, "4. Resource efficiency (supplementary, non-inventory)");
    doc.add(note("\"Avoided\" emissions and cost quantify the effect of semantic "
        + "caching and model routing versus a premium-default baseline. Per the "
        + "GHG Protocol they are an efficiency indicator reported separately and are "
        + "NOT deducted from the GHG inventory above."));
    PdfPTable t = metricTable();
    metricRow(t, "Requests served", String.valueOf(r.totalRequests()), false);
    metricRow(t, "Cache hit rate",
        fmt(r.cacheHitRate() * 100.0, 1) + " %", true);
    metricRow(t, "Avoided emissions",
        fmt(r.totalGramsCo2Avoided() / 1000.0, 3) + " kg CO2e", false);
    metricRow(t, "Cost incurred", fmt(r.totalCostEur(), 4) + " EUR", true);
    metricRow(t, "Cost avoided", fmt(r.totalCostAvoidedEur(), 4) + " EUR", false);
    doc.add(t);
  }

  private static void modelMixSection(Document doc, GreenReport r) throws DocumentException {
    heading(doc, "5. Activity breakdown — model mix");
    PdfPTable t = new PdfPTable(new float[]{3f, 1.2f, 1.2f});
    t.setWidthPercentage(100);
    t.setSpacingBefore(4);
    t.addCell(th("Model"));
    t.addCell(th("Requests"));
    t.addCell(th("Share"));
    boolean zebra = false;
    long total = Math.max(1L, r.totalRequests());
    for (Map.Entry<String, Long> e : r.modelMix().entrySet()) {
      double share = (e.getValue() * 100.0) / total;
      t.addCell(td(e.getKey(), LABEL_FONT, Element.ALIGN_LEFT, zebra));
      t.addCell(td(String.valueOf(e.getValue()), VALUE_FONT, Element.ALIGN_RIGHT, zebra));
      t.addCell(td(fmt(share, 1) + " %", VALUE_FONT, Element.ALIGN_RIGHT, zebra));
      zebra = !zebra;
    }
    if (r.modelMix().isEmpty()) {
      PdfPCell empty = new PdfPCell(new Phrase("No requests in this period.", BODY_FONT));
      empty.setColspan(3);
      empty.setPadding(6);
      t.addCell(empty);
    }
    doc.add(t);
  }

  private static void footer(Document doc) throws DocumentException {
    Paragraph p = new Paragraph(
        "Auto-generated by gatewAI from estimated data. Not an externally assured "
        + "disclosure — see section 1 (Basis of preparation).", SMALL_FONT);
    p.setSpacingBefore(18);
    doc.add(p);
  }

  // --- helpers ---

  private static void heading(Document doc, String text) throws DocumentException {
    Paragraph p = new Paragraph(text, H2_FONT);
    p.setSpacingBefore(14);
    p.setSpacingAfter(4);
    doc.add(p);
  }

  private static Paragraph bullet(String text) {
    Paragraph p = new Paragraph(text, BODY_FONT);
    p.setIndentationLeft(12);
    p.setFirstLineIndent(-8);
    p.setSpacingAfter(3);
    return p;
  }

  private static Paragraph note(String text) {
    Paragraph p = new Paragraph(text, SMALL_FONT);
    p.setSpacingAfter(4);
    return p;
  }

  private static PdfPTable rule() throws DocumentException {
    PdfPTable t = new PdfPTable(1);
    t.setWidthPercentage(100);
    PdfPCell c = new PdfPCell();
    c.setFixedHeight(2.5f);
    c.setBackgroundColor(GREEN_HEADER);
    c.setBorder(0);
    t.addCell(c);
    t.setSpacingBefore(4);
    return t;
  }

  private static PdfPTable metricTable() throws DocumentException {
    PdfPTable t = new PdfPTable(new float[]{3f, 2f});
    t.setWidthPercentage(100);
    t.setSpacingBefore(4);
    t.addCell(th("Metric"));
    t.addCell(th("Value"));
    return t;
  }

  private static void metricRow(PdfPTable t, String label, String value, boolean zebra) {
    t.addCell(td(label, LABEL_FONT, Element.ALIGN_LEFT, zebra));
    t.addCell(td(value, VALUE_FONT, Element.ALIGN_RIGHT, zebra));
  }

  private static PdfPCell th(String text) {
    PdfPCell c = new PdfPCell(new Phrase(text, TH_FONT));
    c.setBackgroundColor(GREEN_HEADER);
    c.setPadding(5);
    c.setBorderColor(Color.WHITE);
    return c;
  }

  private static PdfPCell td(String text, Font font, int align, boolean zebra) {
    PdfPCell c = new PdfPCell(new Phrase(text, font));
    c.setPadding(5);
    c.setHorizontalAlignment(align);
    c.setBorderColor(new Color(220, 220, 220));
    if (zebra) {
      c.setBackgroundColor(ZEBRA);
    }
    return c;
  }

  private static String fmt(double value, int decimals) {
    return String.format(Locale.US, "%,." + decimals + "f", value);
  }
}
