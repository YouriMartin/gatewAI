package io.github.yourimartin.gatewai.eval;

import java.util.List;

/**
 * One labelled cache case: an incoming query, an entry already in the cache, and
 * a human judgment of whether serving that entry's answer would be correct.
 *
 * <p>The judgment answers one question and only one: <i>would the answer stored
 * for {@code entry} correctly and completely answer {@code query}?</i> It is not
 * a similarity rating — two texts can be near-identical and still earn a
 * {@code NO} (an entity swap), or differ widely and earn a {@code YES} (a
 * politeness wrapper).
 *
 * @param id       stable identifier
 * @param query    the incoming request
 * @param entry    the request that produced the cached answer
 * @param servable the label: true when the cached answer would be correct
 * @param language {@code en}, {@code fr} or {@code mixed}
 * @param tags     what makes the case interesting ({@code paraphrase},
 *                 {@code entity-swap}, {@code negation}, {@code near-miss},
 *                 {@code cross-lingual}, {@code volatile}, …)
 */
record CachePair(String id, String query, String entry, boolean servable,
                 String language, List<String> tags) {

  CachePair {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
