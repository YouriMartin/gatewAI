package io.github.yourimartin.gatewai.domain.model.llm;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thrown when a request marked for pass-through reaches a path that cannot
 * forward it (v4 B.1): the request is refused with the features named, rather
 * than served by the advisor chain with those features silently dropped.
 */
public class UnsupportedFeatureException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final PassThroughFeature[] features;

  public UnsupportedFeatureException(Set<PassThroughFeature> features) {
    super(message(features));
    this.features = features.stream().sorted().toArray(PassThroughFeature[]::new);
  }

  /** The features, in declaration order. */
  public List<PassThroughFeature> features() {
    return List.of(features);
  }

  private static String message(Set<PassThroughFeature> features) {
    return "This request uses " + features.stream().sorted()
        .map(PassThroughFeature::description)
        .collect(Collectors.joining(", "))
        + ", which this gateway cannot serve yet. Send text-only messages without these"
        + " fields.";
  }
}
