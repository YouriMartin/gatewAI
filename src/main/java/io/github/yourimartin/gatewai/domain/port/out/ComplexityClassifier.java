package io.github.yourimartin.gatewai.domain.port.out;

import io.github.yourimartin.gatewai.domain.model.ModelTier;

public interface ComplexityClassifier {

  ModelTier classify(String userText);
}
