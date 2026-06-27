package com.example.gatewai.infrastructure.llm;

import java.util.Locale;
import java.util.regex.Pattern;

import com.example.gatewai.domain.model.ModelTier;
import com.example.gatewai.domain.port.out.ComplexityClassifier;

import org.springframework.stereotype.Component;

@Component
class HeuristicComplexityClassifier implements ComplexityClassifier {

  private static final Pattern CODE_BLOCK_PATTERN =
      Pattern.compile("```|~~~");

  private final ClassifierProperties properties;

  HeuristicComplexityClassifier(ClassifierProperties properties) {
    this.properties = properties;
  }

  @Override
  public ModelTier classify(String userText) {
    if (userText == null || userText.isBlank()) {
      return ModelTier.LOCAL;
    }

    if (CODE_BLOCK_PATTERN.matcher(userText).find()) {
      return ModelTier.CLOUD_PREMIUM;
    }

    String lower = userText.toLowerCase(Locale.ROOT);
    for (String keyword : properties.getPremiumKeywords()) {
      if (lower.contains(keyword)) {
        return ModelTier.CLOUD_PREMIUM;
      }
    }

    if (userText.length() > properties.getPremiumLengthThreshold()) {
      return ModelTier.CLOUD_PREMIUM;
    }

    if (userText.length() > properties.getEntryLengthThreshold()) {
      return ModelTier.CLOUD_ENTRY;
    }

    return ModelTier.LOCAL;
  }
}
