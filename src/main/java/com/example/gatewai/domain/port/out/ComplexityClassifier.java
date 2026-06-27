package com.example.gatewai.domain.port.out;

import com.example.gatewai.domain.model.ModelTier;

public interface ComplexityClassifier {

  ModelTier classify(String userText);
}
