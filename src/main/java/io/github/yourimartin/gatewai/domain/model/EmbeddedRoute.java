package io.github.yourimartin.gatewai.domain.model;

import java.util.List;

/**
 * A semantic route with its example prompts already embedded.
 *
 * @param route          the route, its tier and its examples
 * @param exampleVectors one vector per example, positionally aligned with
 *                       {@link SemanticRoute#examples()}
 */
public record EmbeddedRoute(SemanticRoute route, List<float[]> exampleVectors) {

  public EmbeddedRoute {
    exampleVectors = exampleVectors == null ? List.of() : List.copyOf(exampleVectors);
    if (route != null && route.examples().size() != exampleVectors.size()) {
      throw new IllegalArgumentException(
          "every example needs its vector: " + route.examples().size()
              + " examples, " + exampleVectors.size() + " vectors");
    }
  }
}
