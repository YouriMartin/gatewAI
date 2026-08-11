package io.github.yourimartin.gatewai.infrastructure.calibration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.LabelledCase;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.LabelledCaseSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the labelled cases from JSON Lines resources (v2 batch 3).
 *
 * <p>The gateway ships a default set — the calibration halves of the evaluation
 * data from batch 5, 200 labelled prompts and 200 labelled cache pairs — so a
 * fresh install can calibrate immediately instead of shipping thresholds someone
 * guessed and waiting for the operator to produce data. The <b>test</b> halves
 * deliberately stay out of the jar: they exist to check the calibration, and a
 * calibration fitted on its own test set measures nothing.
 *
 * <p>Both locations are Spring resource strings, so an operator points them at
 * their own labelled traffic (`file:/etc/gatewai/routing-labels.jsonl`) without
 * writing code.
 */
@Component
class ClasspathLabelledCaseSource implements LabelledCaseSource {

  private static final Logger LOG =
      LoggerFactory.getLogger(ClasspathLabelledCaseSource.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final DefaultResourceLoader loader = new DefaultResourceLoader();
  private final ConformalProperties properties;

  ClasspathLabelledCaseSource(ConformalProperties properties) {
    this.properties = properties;
  }

  @Override
  public List<LabelledCase.Routing> routingCases() {
    List<LabelledCase.Routing> cases = new ArrayList<>();
    for (JsonNode node : read(properties.getRoutingCases())) {
      cases.add(new LabelledCase.Routing(
          node.path("prompt").asString(),
          ModelTier.valueOf(node.path("expectedTier").asString())));
    }
    return List.copyOf(cases);
  }

  @Override
  public List<LabelledCase.CachePair> cachePairs() {
    List<LabelledCase.CachePair> pairs = new ArrayList<>();
    for (JsonNode node : read(properties.getCacheCases())) {
      pairs.add(new LabelledCase.CachePair(
          node.path("query").asString(),
          node.path("entry").asString(),
          "YES".equals(node.path("judgment").asString())));
    }
    return List.copyOf(pairs);
  }

  @Override
  public String description() {
    return properties.getRoutingCases() + " + " + properties.getCacheCases();
  }

  private List<JsonNode> read(String location) {
    Resource resource = loader.getResource(location);
    if (!resource.exists()) {
      throw new IllegalStateException("Labelled case set not found: " + location);
    }

    List<JsonNode> nodes = new ArrayList<>();
    try (InputStream in = resource.getInputStream();
         BufferedReader reader = new BufferedReader(
             new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          nodes.add(MAPPER.readTree(line));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + location, e);
    }
    LOG.debug("Loaded {} labelled cases from {}", nodes.size(), location);
    return nodes;
  }
}
