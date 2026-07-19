package io.github.yourimartin.gatewai.domain.model;

import java.util.List;

/**
 * A semantic routing route: a named intent bucket mapped to a model tier and
 * defined by a handful of example prompts ("utterances"). A request is matched
 * to the route whose examples are semantically closest (embedding cosine
 * similarity), independently of language or exact wording.
 *
 * @param name     short identifier shown in logs and the dashboard
 * @param tier     the model tier requests matching this route are sent to
 * @param examples representative prompts; 5–15 short examples work well
 */
public record SemanticRoute(String name, ModelTier tier, List<String> examples) {

  public SemanticRoute {
    examples = examples == null ? List.of() : List.copyOf(examples);
  }
}
