package io.github.yourimartin.gatewai.domain.port.out;

import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;

/**
 * Classifies a request into the cheapest model tier that can handle it.
 *
 * <p>Returns the tier <b>and</b> the reason for it (v2 batch 1): an explanation
 * that only existed for one strategy would disappear the moment
 * {@code gatewai.classifier.strategy} changed.
 */
public interface ComplexityClassifier {

  ClassificationOutcome classify(String userText);
}
