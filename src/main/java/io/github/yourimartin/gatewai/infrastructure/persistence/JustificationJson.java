package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.CascadeLevel;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.FallbackCause;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.HeuristicRule;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.RouteCandidate;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.ModelTier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps a {@link ClassificationJustification} to and from the JSONB column.
 *
 * <p>Written by hand rather than with Jackson annotations for two reasons: the
 * domain records stay free of any framework, and the {@code switch} below is
 * <b>exhaustive over a sealed interface</b> — adding a justification variant
 * without deciding how it is stored will not compile. That is the whole point
 * of having sealed it.
 */
final class JustificationJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String TYPE = "type";
  private static final String HEURISTIC = "heuristic";
  private static final String EMBEDDING = "embedding";
  private static final String LLM = "llm";
  private static final String FALLBACK = "fallback";
  private static final String FAIL_SAFE = "failSafe";
  private static final String CASCADE = "cascade";

  private JustificationJson() {
  }

  static String toJson(ClassificationJustification justification) {
    return justification == null ? null : MAPPER.writeValueAsString(
        toNode(justification));
  }

  static ClassificationJustification fromJson(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    return fromNode(MAPPER.readTree(json));
  }

  private static ObjectNode toNode(ClassificationJustification justification) {
    ObjectNode node = MAPPER.createObjectNode();
    switch (justification) {
      case ClassificationJustification.Heuristic heuristic -> {
        node.put(TYPE, HEURISTIC);
        node.put("rule", heuristic.rule().name());
        node.put("matchedKeyword", heuristic.matchedKeyword());
        putNullableInt(node, "observedLength", heuristic.observedLength());
        putNullableInt(node, "threshold", heuristic.threshold());
      }
      case ClassificationJustification.Embedding embedding -> {
        node.put(TYPE, EMBEDDING);
        node.put("topScore", embedding.topScore());
        node.put("margin", embedding.margin());
        node.put("threshold", embedding.threshold());
        ArrayNode candidates = node.putArray("candidates");
        for (RouteCandidate candidate : embedding.candidates()) {
          ObjectNode entry = candidates.addObject();
          entry.put("route", candidate.route());
          entry.put("tier", candidate.tier() == null
              ? null : candidate.tier().name());
          entry.put("bestUtterance", candidate.bestUtterance());
          entry.put("score", candidate.score());
          entry.put("rank", candidate.rank());
        }
      }
      case ClassificationJustification.Llm llm -> {
        node.put(TYPE, LLM);
        node.put("reasoning", llm.reasoning());
        node.put("classifierModelId", llm.classifierModelId());
      }
      case ClassificationJustification.Fallback fallback -> {
        node.put(TYPE, FALLBACK);
        node.put("fallbackFrom", fallback.fallbackFrom().name());
        node.put("cause", fallback.cause().name());
        node.set("effective", toNode(fallback.effective()));
        node.set("evidence", fallback.evidence() == null
            ? null : toNode(fallback.evidence()));
      }
      case ClassificationJustification.Cascade cascade -> {
        node.put(TYPE, CASCADE);
        node.put("level", cascade.level().name());
        node.put("marginBand", cascade.marginBand());
        node.set("decided", toNode(cascade.decided()));
        node.set("escalatedOn", cascade.escalatedOn() == null
            ? null : toNode(cascade.escalatedOn()));
      }
      case ClassificationJustification.FailSafe failSafe -> {
        node.put(TYPE, FAIL_SAFE);
        node.put("fallbackFrom", failSafe.fallbackFrom().name());
        node.put("cause", failSafe.cause().name());
      }
      default -> throw new IllegalArgumentException(
          "Unsupported justification: " + justification.getClass());
    }
    return node;
  }

  private static ClassificationJustification fromNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String type = text(node, TYPE);
    return switch (type == null ? "" : type) {
      case HEURISTIC -> new ClassificationJustification.Heuristic(
          HeuristicRule.valueOf(text(node, "rule")),
          text(node, "matchedKeyword"),
          nullableInt(node, "observedLength"),
          nullableInt(node, "threshold"));
      case EMBEDDING -> new ClassificationJustification.Embedding(
          candidates(node.get("candidates")),
          node.path("topScore").asDouble(),
          node.path("margin").asDouble(),
          node.path("threshold").asDouble());
      case LLM -> new ClassificationJustification.Llm(
          text(node, "reasoning"), text(node, "classifierModelId"));
      case FALLBACK -> new ClassificationJustification.Fallback(
          ClassificationStrategy.valueOf(text(node, "fallbackFrom")),
          FallbackCause.valueOf(text(node, "cause")),
          fromNode(node.get("effective")),
          fromNode(node.get("evidence")));
      case CASCADE -> new ClassificationJustification.Cascade(
          CascadeLevel.valueOf(text(node, "level")),
          node.path("marginBand").asDouble(),
          fromNode(node.get("decided")),
          fromNode(node.get("escalatedOn")));
      case FAIL_SAFE -> new ClassificationJustification.FailSafe(
          ClassificationStrategy.valueOf(text(node, "fallbackFrom")),
          FallbackCause.valueOf(text(node, "cause")));
      default -> throw new IllegalArgumentException(
          "Unknown justification type: " + type);
    };
  }

  private static List<RouteCandidate> candidates(JsonNode array) {
    List<RouteCandidate> candidates = new ArrayList<>();
    if (array == null || !array.isArray()) {
      return candidates;
    }
    for (JsonNode entry : array) {
      String tier = text(entry, "tier");
      candidates.add(new RouteCandidate(
          text(entry, "route"),
          tier == null ? null : ModelTier.valueOf(tier),
          text(entry, "bestUtterance"),
          entry.path("score").asDouble(),
          entry.path("rank").asInt()));
    }
    return candidates;
  }

  private static void putNullableInt(ObjectNode node, String field,
                                     Integer value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value.intValue());
    }
  }

  private static Integer nullableInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asInt();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }
}
