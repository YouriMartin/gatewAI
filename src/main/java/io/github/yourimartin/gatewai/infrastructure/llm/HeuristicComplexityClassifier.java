package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.Locale;
import java.util.regex.Pattern;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.HeuristicRule;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;

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
  public ClassificationOutcome classify(String userText) {
    if (userText == null || userText.isBlank()) {
      return outcome(ModelTier.LOCAL,
          ClassificationJustification.Heuristic.of(HeuristicRule.BLANK_TEXT));
    }

    if (CODE_BLOCK_PATTERN.matcher(userText).find()) {
      return outcome(ModelTier.CLOUD_PREMIUM,
          ClassificationJustification.Heuristic.of(HeuristicRule.CODE_FENCE));
    }

    String lower = userText.toLowerCase(Locale.ROOT);
    for (String keyword : properties.getPremiumKeywords()) {
      if (lower.contains(keyword)) {
        return outcome(ModelTier.CLOUD_PREMIUM,
            ClassificationJustification.Heuristic.keyword(keyword));
      }
    }

    int premiumThreshold = properties.getPremiumLengthThreshold();
    if (userText.length() > premiumThreshold) {
      return outcome(ModelTier.CLOUD_PREMIUM,
          ClassificationJustification.Heuristic.length(
              HeuristicRule.PREMIUM_LENGTH, userText.length(),
              premiumThreshold));
    }

    int entryThreshold = properties.getEntryLengthThreshold();
    if (userText.length() > entryThreshold) {
      return outcome(ModelTier.CLOUD_ENTRY,
          ClassificationJustification.Heuristic.length(
              HeuristicRule.ENTRY_LENGTH, userText.length(), entryThreshold));
    }

    return outcome(ModelTier.LOCAL,
        ClassificationJustification.Heuristic.of(HeuristicRule.DEFAULT));
  }

  private static ClassificationOutcome outcome(
      ModelTier tier, ClassificationJustification justification) {
    return new ClassificationOutcome(tier, justification);
  }
}
