package io.github.yourimartin.gatewai.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.infrastructure.llm.EvalClassifierFactory;

/**
 * The configuration the gateway actually ships with (v2 batch 5).
 *
 * <p>The harness scores the shipped defaults, not a set of numbers copied into
 * a test: {@code application.properties} is read from the main classpath and its
 * {@code gatewai.*} values are what the evaluation runs on. Lower a threshold in
 * production configuration and the next report says what it cost.
 *
 * <p>Read with plain {@link Properties} rather than a Spring context on purpose
 * — the harness must run without a database, a broker or a model server.
 */
final class EvalConfig {

  private static final String RESOURCE = "/application.properties";
  private static final String CLASSIFIER_PREFIX = "gatewai.classifier.";
  private static final String REGISTRY_PREFIX = "gatewai.models.registry.";

  private final Properties properties;

  private EvalConfig(Properties properties) {
    this.properties = properties;
  }

  static EvalConfig load() {
    Properties properties = new Properties();
    try (InputStream in = EvalConfig.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing " + RESOURCE + " on the classpath");
      }
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + RESOURCE, e);
    }
    return new EvalConfig(properties);
  }

  /**
   * The routing rules in force: the {@code ClassifierProperties} defaults, with
   * every {@code gatewai.classifier.*} override from the properties file applied.
   *
   * <p>Routes declared in properties are rejected rather than ignored. Silently
   * evaluating the code defaults while production runs on configured routes is
   * exactly the kind of quiet lie this batch exists to prevent.
   */
  RoutingConfig routingConfig() {
    RoutingConfig defaults = EvalClassifierFactory.defaultRoutingConfig();
    if (properties.stringPropertyNames().stream()
        .anyMatch(key -> key.startsWith(CLASSIFIER_PREFIX + "routes"))) {
      throw new IllegalStateException(
          "application.properties declares semantic routes; extend EvalConfig to bind them");
    }
    return new RoutingConfig(
        string(CLASSIFIER_PREFIX + "strategy", defaults.strategy()),
        integer(CLASSIFIER_PREFIX + "entry-length-threshold", defaults.entryLengthThreshold()),
        integer(CLASSIFIER_PREFIX + "premium-length-threshold", defaults.premiumLengthThreshold()),
        list(CLASSIFIER_PREFIX + "premium-keywords", defaults.premiumKeywords()),
        number(CLASSIFIER_PREFIX + "route-similarity-threshold",
            defaults.routeSimilarityThreshold()),
        defaults.routes());
  }

  /** The embedding model both the cache and the router run on. */
  String embeddingModelId() {
    return string("spring.ai.ollama.embedding.options.model", "nomic-embed-text");
  }

  /** Similarity a candidate must reach for the cache to serve it. */
  double cacheSimilarityThreshold() {
    return number("gatewai.cache.similarity-threshold", 0.92);
  }

  /**
   * The cascade's ambiguity band (v2 batch 4): how close the runner-up route
   * has to be for the classifier model to be worth calling.
   */
  double cascadeMarginBand() {
    return number(CLASSIFIER_PREFIX + "cascade-margin-band", 0.02);
  }

  /** Risk level the routing calibration is fitted at (v2 batch 3). */
  double routingAlpha() {
    return number("gatewai.conformal.routing-alpha", 0.10);
  }

  /** Risk level the cache calibration is fitted at — the tighter of the two. */
  double cacheAlpha() {
    return number("gatewai.conformal.cache-alpha", 0.10);
  }

  /** Static grid carbon intensity, gCO2 per kWh. */
  double gridIntensityGramsPerKwh() {
    return number("gatewai.carbon.grid-intensity-grams-per-kwh", 230.0);
  }

  /**
   * The model registry, in declaration order — the order matters, because the
   * router takes the first model registered for a tier.
   */
  List<ModelDefinition> models() {
    Map<String, Map<String, String>> byKey = new LinkedHashMap<>();
    for (String property : orderedKeys()) {
      if (!property.startsWith(REGISTRY_PREFIX)) {
        continue;
      }
      String remainder = property.substring(REGISTRY_PREFIX.length());
      int separator = remainder.lastIndexOf('.');
      if (separator < 0) {
        continue;
      }
      byKey.computeIfAbsent(remainder.substring(0, separator), key -> new LinkedHashMap<>())
          .put(remainder.substring(separator + 1), properties.getProperty(property));
    }

    List<ModelDefinition> models = new ArrayList<>();
    byKey.forEach((key, entry) -> models.add(new ModelDefinition(
        key,
        entry.getOrDefault("provider", ""),
        entry.getOrDefault("model-id", ""),
        Double.parseDouble(entry.getOrDefault("cost-per-1k-tokens", "0")),
        Double.parseDouble(entry.getOrDefault("energy-intensity", "0")),
        tier(entry.get("tier")))));
    return List.copyOf(models);
  }

  /** The model the router would pick for {@code tier}: the first registered. */
  ModelDefinition modelFor(ModelTier tier) {
    return models().stream()
        .filter(model -> model.tier() == tier)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No model registered for tier " + tier));
  }

  /**
   * Property keys in file order. {@link Properties} is a hash table, so its own
   * iteration order would silently reshuffle the registry and change which model
   * a tier resolves to.
   */
  private List<String> orderedKeys() {
    List<String> keys = new ArrayList<>();
    try (InputStream in = EvalConfig.class.getResourceAsStream(RESOURCE)) {
      Properties ordered = new Properties() {
        @Override
        public synchronized Object put(Object key, Object value) {
          keys.add(String.valueOf(key));
          return super.put(key, value);
        }
      };
      ordered.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + RESOURCE, e);
    }
    return keys;
  }

  private static ModelTier tier(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return ModelTier.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
  }

  private String string(String key, String fallback) {
    String value = properties.getProperty(key);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private int integer(String key, int fallback) {
    String value = properties.getProperty(key);
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
  }

  private double number(String key, double fallback) {
    String value = properties.getProperty(key);
    return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
  }

  private List<String> list(String key, List<String> fallback) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return List.of(value.split("\\s*,\\s*"));
  }
}
