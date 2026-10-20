package io.github.yourimartin.gatewai.domain.model.llm;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thrown when a request marked for pass-through reaches a path that cannot
 * forward it (v4 B.1), or a target whose endpoint would ignore one of its
 * features (ADR 0016, v4 B.3). The request is refused with the features named,
 * rather than served with those features silently dropped.
 */
public class UnsupportedFeatureException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final PassThroughFeature[] features;

  /** A marked request on a path with no pass-through: the deferred endpoint, MCP. */
  public UnsupportedFeatureException(Set<PassThroughFeature> features) {
    this(features, "This request uses " + describe(features)
        + ", which gatewAI forwards to the model as-is only on POST /v1/chat/completions."
        + " Send it to that endpoint, or send text-only messages without these fields.");
  }

  private UnsupportedFeatureException(Set<PassThroughFeature> features, String message) {
    super(message);
    this.features = features.stream().sorted().toArray(PassThroughFeature[]::new);
  }

  /**
   * A pass-through request whose target endpoint is documented to ignore some of
   * its features (ADR 0016).
   *
   * @param ignored  the features the target would drop
   * @param modelId  the registry model id of the target
   * @param endpoint what the target's endpoint is, for the client to read
   */
  public static UnsupportedFeatureException notHonouredBy(Set<PassThroughFeature> ignored,
                                                          String modelId, String endpoint) {
    return new UnsupportedFeatureException(ignored, "This request uses " + describe(ignored)
        + ", which model '" + modelId + "' would silently ignore (" + endpoint + ")."
        + " Pin a model on another provider, or remove these fields.");
  }

  /** The features, in declaration order. */
  public List<PassThroughFeature> features() {
    return List.of(features);
  }

  private static String describe(Set<PassThroughFeature> features) {
    return features.stream().sorted()
        .map(PassThroughFeature::description)
        .collect(Collectors.joining(", "));
  }
}
