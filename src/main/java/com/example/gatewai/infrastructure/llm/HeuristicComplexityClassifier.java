package com.example.gatewai.infrastructure.llm;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.example.gatewai.domain.model.ModelTier;
import com.example.gatewai.domain.port.out.ComplexityClassifier;

import org.springframework.stereotype.Component;

@Component
class HeuristicComplexityClassifier implements ComplexityClassifier {

  static final int PREMIUM_LENGTH_THRESHOLD = 500;
  static final int ENTRY_LENGTH_THRESHOLD = 100;

  private static final Pattern CODE_BLOCK_PATTERN =
      Pattern.compile("```|~~~");

  private static final List<String> PREMIUM_KEYWORDS = List.of(
      "refactor", "architecture", "demonstrate", "démontrer",
      "analyze", "analyser", "optimize", "optimiser",
      "debug", "algorithm", "algorithme",
      "security", "sécurité", "vulnerability", "vulnérabilité",
      "design pattern", "scalab", "migrat"
  );

  @Override
  public ModelTier classify(String userText) {
    if (userText == null || userText.isBlank()) {
      return ModelTier.LOCAL;
    }

    if (CODE_BLOCK_PATTERN.matcher(userText).find()) {
      return ModelTier.CLOUD_PREMIUM;
    }

    String lower = userText.toLowerCase(Locale.ROOT);
    for (String keyword : PREMIUM_KEYWORDS) {
      if (lower.contains(keyword)) {
        return ModelTier.CLOUD_PREMIUM;
      }
    }

    if (userText.length() > PREMIUM_LENGTH_THRESHOLD) {
      return ModelTier.CLOUD_PREMIUM;
    }

    if (userText.length() > ENTRY_LENGTH_THRESHOLD) {
      return ModelTier.CLOUD_ENTRY;
    }

    return ModelTier.LOCAL;
  }
}
