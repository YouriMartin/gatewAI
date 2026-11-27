package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import io.github.yourimartin.gatewai.domain.model.calibration.FitSummary;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code conformal_calibration.fit_summary} as JSON (v4 D.3, ADR 0019). Written
 * field by field, like {@link JustificationJson}, so the domain stays free of
 * serialization annotations and the stored shape is spelled out in one place.
 *
 * <p>Every document carries {@code version}: a summary outlives the code that
 * wrote it, and a reader must know which shape it holds. An unknown version or
 * type reads as no summary rather than failing the calibration it describes.
 */
final class FitSummaryJson {

  /** Bumped on any change to the stored shape. */
  static final int VERSION = 1;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String TYPE = "type";
  private static final String LABELLED_SET = "labelledSet";
  private static final String SHADOW = "shadow";

  private FitSummaryJson() {
  }

  static String toJson(FitSummary summary) {
    if (summary == null) {
      return null;
    }
    ObjectNode node = MAPPER.createObjectNode();
    node.put("version", VERSION);
    switch (summary) {
      case FitSummary.LabelledSet labelled -> {
        node.put(TYPE, LABELLED_SET);
        node.put("source", labelled.source());
        node.put("read", labelled.read());
        node.put("fitted", labelled.fitted());
      }
      case FitSummary.Shadow shadow -> {
        node.put(TYPE, SHADOW);
        FitSummary.Exclusions ex = shadow.exclusions();
        ObjectNode exclusions = node.putObject("exclusions");
        exclusions.put("notSampled", ex.notSampled());
        exclusions.put("read", ex.read());
        exclusions.put("failed", ex.failed());
        exclusions.put("unsure", ex.unsure());
        exclusions.put("noDecision", ex.noDecision());
        exclusions.put("otherRubric", ex.otherRubric());
        exclusions.put("otherJudge", ex.otherJudge());
        exclusions.put("otherEmbedding", ex.otherEmbedding());
        exclusions.put("recordedAboveFloor", ex.recordedAboveFloor());
        exclusions.put("belowFloor", ex.belowFloor());
        node.put("yes", shadow.yes());
        node.put("no", shadow.no());
        node.put("labelsFrom", instant(shadow.labelsFrom()));
        node.put("labelsTo", instant(shadow.labelsTo()));
        node.put("refusedServableShare", shadow.refusedServableShare());
        node.put("ambiguousBecomingServable", shadow.ambiguousBecomingServable());
        node.put("modelledRateP90", shadow.modelledRateP90());
        node.put("minPairs", shadow.minPairs());
        node.put("minNegatives", shadow.minNegatives());
        ObjectNode clients = node.putObject("perClient");
        shadow.perClient().forEach((client, counts) -> {
          ObjectNode entry = clients.putObject(client);
          entry.put("read", counts.read());
          entry.put("yes", counts.yes());
          entry.put("no", counts.no());
        });
      }
    }
    return MAPPER.writeValueAsString(node);
  }

  static FitSummary fromJson(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    JsonNode node = MAPPER.readTree(json);
    if (node.path("version").asInt() != VERSION) {
      return null;
    }
    return switch (node.path(TYPE).asString()) {
      case LABELLED_SET -> new FitSummary.LabelledSet(node.path("source").asString(),
          node.path("read").asInt(), node.path("fitted").asInt());
      case SHADOW -> shadow(node);
      default -> null;
    };
  }

  private static FitSummary.Shadow shadow(JsonNode node) {
    JsonNode ex = node.path("exclusions");
    FitSummary.Exclusions exclusions = new FitSummary.Exclusions(
        ex.path("notSampled").asLong(), ex.path("read").asInt(), ex.path("failed").asInt(),
        ex.path("unsure").asInt(), ex.path("noDecision").asInt(),
        ex.path("otherRubric").asInt(), ex.path("otherJudge").asInt(),
        ex.path("otherEmbedding").asInt(), ex.path("recordedAboveFloor").asInt(),
        ex.path("belowFloor").asInt());
    SortedMap<String, FitSummary.ClientCounts> perClient = new TreeMap<>();
    for (Map.Entry<String, JsonNode> entry : node.path("perClient").properties()) {
      JsonNode counts = entry.getValue();
      perClient.put(entry.getKey(), new FitSummary.ClientCounts(
          counts.path("read").asInt(), counts.path("yes").asInt(), counts.path("no").asInt()));
    }
    JsonNode refused = node.path("refusedServableShare");
    return new FitSummary.Shadow(exclusions, node.path("yes").asInt(),
        node.path("no").asInt(), toInstant(node.path("labelsFrom")),
        toInstant(node.path("labelsTo")),
        refused.isNumber() ? refused.asDouble() : null,
        node.path("ambiguousBecomingServable").asLong(),
        node.path("modelledRateP90").asDouble(), node.path("minPairs").asInt(),
        node.path("minNegatives").asInt(), perClient);
  }

  private static String instant(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private static Instant toInstant(JsonNode value) {
    return value.isString() ? Instant.parse(value.asString()) : null;
  }
}
