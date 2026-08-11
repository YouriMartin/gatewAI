package io.github.yourimartin.gatewai.eval;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ModelTier;

/**
 * One labelled routing case: a prompt and the tier a human says it deserves.
 *
 * @param id           stable identifier, so a regression names the case it broke
 * @param prompt       the request text, exactly as a client would send it
 * @param expectedTier the label
 * @param language     {@code en}, {@code fr} or {@code mixed}
 * @param tags         what makes the case interesting ({@code nominal},
 *                     {@code ambiguous}, {@code ood}, {@code keyword-trap},
 *                     {@code length-trap}, {@code short-premium}, {@code long},
 *                     {@code code})
 */
record RoutingSample(String id, String prompt, ModelTier expectedTier,
                     String language, List<String> tags) {

  RoutingSample {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
