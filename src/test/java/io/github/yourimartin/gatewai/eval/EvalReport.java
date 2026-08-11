package io.github.yourimartin.gatewai.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.ModelTier;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The run's report (v2 batch 5): machine-readable JSON for CI to diff across
 * commits, and Markdown for a human to read when the diff is not obvious.
 *
 * <p>All six metrics from the plan appear, including the two that cannot be
 * measured yet. {@code escalationRate} needs batch 4's cascade and
 * {@code conformalCoverage} needs batch 3's calibration; both are emitted as
 * {@code null} with the reason attached rather than omitted, so the report's
 * shape stops changing and the gaps stay visible.
 */
final class EvalReport {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MISSES_SHOWN = 15;

  private final ObjectNode root = MAPPER.createObjectNode();
  private final StringBuilder markdown = new StringBuilder();

  EvalReport(FixtureProvenance provenance, String currentRoutingConfigVersion) {
    boolean stale = !provenance.routingConfigVersion().equals(currentRoutingConfigVersion);

    root.put("generatedAt", Instant.now().toString());
    ObjectNode source = root.putObject("provenance");
    source.put("embeddingModel", provenance.embeddingModel());
    source.put("dimensions", provenance.dimensions());
    source.put("fixturesRecordedAt", provenance.recordedAt());
    source.put("datasetDigest", provenance.datasetDigest());
    source.put("routingConfigVersion", provenance.routingConfigVersion());
    source.put("currentRoutingConfigVersion", currentRoutingConfigVersion);
    source.put("status", stale ? "STALE" : "VALID");

    markdown.append("# gatewAI evaluation report\n\n")
        .append("Generated ").append(Instant.now()).append(".\n\n")
        .append("| Provenance | |\n|---|---|\n")
        .append("| Embedding model | `").append(provenance.embeddingModel()).append("` |\n")
        .append("| Fixtures recorded | ").append(provenance.recordedAt()).append(" |\n")
        .append("| Dataset digest | `").append(provenance.datasetDigest()).append("` |\n")
        .append("| Routing config version | `").append(provenance.routingConfigVersion())
        .append("` |\n")
        .append("| Status | ").append(stale ? "**STALE**" : "VALID").append(" |\n\n");
  }

  void routing(String key, RoutingEvaluator.Result result) {
    ObjectNode node = metrics().putObject(key);
    node.put("dataset", result.dataset());
    node.put("strategy", result.strategy());
    node.put("total", result.total());
    node.put("correct", result.correct());
    node.put("accuracy", round(result.accuracy()));
    node.put("overRouted", result.overRouted());
    node.put("underRouted", result.underRouted());
    node.put("meanMargin", round(result.meanMargin()));

    ObjectNode confusion = node.putObject("confusion");
    result.confusion().forEach((expected, actuals) -> {
      ObjectNode row = confusion.putObject(expected.name());
      actuals.forEach((actual, count) -> row.put(actual.name(), count));
    });
    putTiers(node.putObject("predictedTiers"), result.predictedTiers());
    putScores(node.putObject("byTag"), result.byTag());
    putScores(node.putObject("byLanguage"), result.byLanguage());
    putCounts(node.putObject("effectiveStrategies"), result.effectiveStrategies());
    putCounts(node.putObject("fallbackCauses"), result.fallbackCauses());

    ArrayNode misses = node.putArray("misses");
    for (RoutingEvaluator.Miss miss : result.misses()) {
      ObjectNode entry = misses.addObject();
      entry.put("id", miss.id());
      entry.put("expected", miss.expected().name());
      entry.put("actual", miss.actual().name());
      entry.put("tags", String.join(",", miss.tags()));
    }

    markdown.append("## Routing — ").append(key).append("\n\n")
        .append("`").append(result.strategy()).append("` on `")
        .append(result.dataset()).append("` (n=").append(result.total()).append(")\n\n")
        .append("| Metric | Value |\n|---|---|\n")
        .append("| Accuracy | ").append(percent(result.accuracy())).append(" |\n")
        .append("| Over-routed (too expensive) | ").append(result.overRouted()).append(" |\n")
        .append("| Under-routed (too cheap) | ").append(result.underRouted()).append(" |\n")
        .append("| Mean margin (top1−top2) | ").append(round(result.meanMargin()))
        .append(" |\n\n");

    markdown.append("| Expected \\ actual | LOCAL | CLOUD_ENTRY | CLOUD_PREMIUM |\n")
        .append("|---|---|---|---|\n");
    for (ModelTier expected : ModelTier.values()) {
      Map<ModelTier, Integer> row = result.confusion().getOrDefault(expected, Map.of());
      markdown.append("| ").append(expected.name());
      for (ModelTier actual : ModelTier.values()) {
        markdown.append(" | ").append(row.getOrDefault(actual, 0));
      }
      markdown.append(" |\n");
    }
    markdown.append('\n');

    appendScores("Accuracy by tag", result.byTag());
    appendScores("Accuracy by language", result.byLanguage());
    appendCounts("Effective strategy", result.effectiveStrategies());
    appendCounts("Fallback causes", result.fallbackCauses());

    if (!result.misses().isEmpty()) {
      markdown.append("Misrouted (first ").append(MISSES_SHOWN).append("): ");
      markdown.append(String.join(", ", result.misses().stream()
          .limit(MISSES_SHOWN)
          .map(miss -> miss.id() + " (" + miss.expected() + "→" + miss.actual() + ")")
          .toList()));
      markdown.append("\n\n");
    }
  }

  void cache(String key, CacheEvaluator.Result result) {
    CacheEvaluator.Confusion confusion = result.confusion();
    ObjectNode node = metrics().putObject(key);
    node.put("dataset", result.dataset());
    node.put("threshold", result.threshold());
    node.put("total", result.total());
    node.put("accuracy", round(confusion.accuracy()));
    node.put("hitRate", round(confusion.hitRate()));
    node.put("falsePositives", confusion.falsePositives());
    node.put("falseNegatives", confusion.falseNegatives());
    node.put("falsePositiveRate", round(confusion.falsePositiveRate()));
    node.put("falseNegativeRate", round(confusion.falseNegativeRate()));
    node.put("falsePositiveIds", String.join(",", result.falsePositiveIds()));

    ArrayNode sweep = node.putArray("thresholdSweep");
    for (CacheEvaluator.Point point : result.sweep()) {
      ObjectNode entry = sweep.addObject();
      entry.put("threshold", round(point.threshold()));
      entry.put("falsePositiveRate", round(point.falsePositiveRate()));
      entry.put("falseNegativeRate", round(point.falseNegativeRate()));
      entry.put("hitRate", round(point.hitRate()));
    }
    ObjectNode byTag = node.putObject("byTag");
    result.byTag().forEach((tag, score) -> byTag.put(tag, round(score.accuracy())));

    markdown.append("## Cache — ").append(key).append("\n\n")
        .append("`").append(result.dataset()).append("` (n=").append(result.total())
        .append(") at threshold ").append(result.threshold()).append("\n\n")
        .append("| Metric | Value |\n|---|---|\n")
        .append("| Accuracy | ").append(percent(confusion.accuracy())).append(" |\n")
        .append("| Hit rate | ").append(percent(confusion.hitRate())).append(" |\n")
        .append("| False positives (wrong answer served) | ")
        .append(confusion.falsePositives()).append(" — ")
        .append(percent(confusion.falsePositiveRate())).append(" |\n")
        .append("| False negatives (needless model call) | ")
        .append(confusion.falseNegatives()).append(" — ")
        .append(percent(confusion.falseNegativeRate())).append(" |\n\n");

    if (!result.falsePositiveIds().isEmpty()) {
      markdown.append("Wrongly served: ")
          .append(String.join(", ", result.falsePositiveIds())).append("\n\n");
    }
  }

  void savings(SavingsEstimator.Estimate estimate, RoutingEvaluator.Result routing) {
    ObjectNode node = metrics().putObject("estimatedSavings");
    node.put("underRouted", routing.underRouted());
    node.put("requests", estimate.requests());
    node.put("totalTokens", estimate.totalTokens());
    node.put("assumedCompletionTokens", estimate.assumedCompletionTokens());
    node.put("gridIntensityGramsPerKwh", estimate.gridIntensityGramsPerKwh());
    node.put("baselineModelId", estimate.baselineModelId());
    node.put("routedCost", round(estimate.routedCost()));
    node.put("baselineCost", round(estimate.baselineCost()));
    node.put("costSaved", round(estimate.costSaved()));
    node.put("costSavedRatio", round(estimate.costSavedRatio()));
    node.put("routedGramsCo2", round(estimate.routedGramsCo2()));
    node.put("baselineGramsCo2", round(estimate.baselineGramsCo2()));
    node.put("gramsCo2Saved", round(estimate.gramsCo2Saved()));
    node.put("gramsCo2SavedRatio", round(estimate.gramsCo2SavedRatio()));

    markdown.append("## Estimated savings vs an all-premium baseline\n\n")
        .append("Baseline model `").append(estimate.baselineModelId()).append("`, ")
        .append(estimate.requests()).append(" requests, ")
        .append(estimate.totalTokens()).append(" tokens ")
        .append("(prompt ≈ chars/4, ").append(estimate.assumedCompletionTokens())
        .append(" completion tokens assumed per request), grid ")
        .append(estimate.gridIntensityGramsPerKwh()).append(" gCO2/kWh.\n\n")
        .append("| Metric | Routed | All-premium | Saved |\n|---|---|---|---|\n")
        .append("| Cost | ").append(round(estimate.routedCost())).append(" | ")
        .append(round(estimate.baselineCost())).append(" | ")
        .append(round(estimate.costSaved())).append(" (")
        .append(percent(estimate.costSavedRatio())).append(") |\n")
        .append("| gCO2 | ").append(round(estimate.routedGramsCo2())).append(" | ")
        .append(round(estimate.baselineGramsCo2())).append(" | ")
        .append(round(estimate.gramsCo2Saved())).append(" (")
        .append(percent(estimate.gramsCo2SavedRatio())).append(") |\n\n")
        .append("Read this next to under-routing: ").append(routing.underRouted())
        .append(" of ").append(routing.total())
        .append(" requests went to a cheaper tier than their label, so part of what ")
        .append("this column calls a saving is answer quality given away rather than ")
        .append("efficiency gained. A gateway can always reach 100% by sending ")
        .append("everything to the smallest model.\n\n");
  }

  void decisionLatency(LatencyStats latency) {
    ObjectNode node = metrics().putObject("decisionLatencyMillis");
    node.put("p50", round(latency.p50()));
    node.put("p95", round(latency.p95()));
    node.put("samples", latency.samples());
    node.put("measurement", "recorded live at fixture time; excludes the model call");

    markdown.append("## Decision latency\n\n")
        .append("Classifier only, no model call. Measured live while recording the ")
        .append("fixtures, so it describes the recording machine — a replay run is a ")
        .append("hash-map lookup and would measure nothing.\n\n")
        .append("| p50 | p95 | samples |\n|---|---|---|\n| ")
        .append(round(latency.p50())).append(" ms | ")
        .append(round(latency.p95())).append(" ms | ")
        .append(latency.samples()).append(" |\n\n");
  }

  /**
   * The conformal metrics (v2 batch 3): what each calibration promised, and what
   * it delivered on a test set it was not fitted on.
   */
  void conformal(ConformalCalibration routing, HarnessCalibrator.Coverage coverage,
                 ConformalCalibration cache, HarnessCalibrator.Coverage wrongAnswers) {

    ObjectNode node = metrics().putObject("conformalCoverage");
    putCalibration(node.putObject("routing"), routing, coverage,
        "share of test prompts whose correct route is inside the prediction set");
    putCalibration(node.putObject("cache"), cache, wrongAnswers,
        "share of non-servable test pairs the threshold would serve anyway");

    markdown.append("## Conformal calibration\n\n")
        .append("Fitted on the calibration halves, measured on the disjoint test ")
        .append("halves. A guarantee stated at α is worth nothing until the ")
        .append("empirical rate on unseen data lands near it.\n\n")
        .append("| Target | Guarantee | α | Threshold | Fitted on | Promised | Measured | 1 s.e. |\n")
        .append("|---|---|---|---|---|---|---|---|\n");
    appendCalibration("routing", routing, coverage);
    appendCalibration("cache", cache, wrongAnswers);
    markdown.append('\n');
  }

  private void putCalibration(ObjectNode node, ConformalCalibration calibration,
                              HarnessCalibrator.Coverage coverage, String meaning) {
    node.put("guarantee", calibration.guarantee().name());
    node.put("alpha", round(calibration.alpha()));
    node.put("qHat", round(calibration.qhat()));
    node.put("threshold", round(calibration.similarityThreshold()));
    node.put("calibrationSampleSize", calibration.sampleSize());
    node.put("testSampleSize", coverage.sampleSize());
    node.put("target", round(coverage.target()));
    node.put("measured", round(coverage.rate()));
    node.put("standardError", round(coverage.standardError()));
    node.put("meaning", meaning);
  }

  private void appendCalibration(String target, ConformalCalibration calibration,
                                 HarnessCalibrator.Coverage coverage) {
    markdown.append("| ").append(target)
        .append(" | ").append(calibration.guarantee())
        .append(" | ").append(round(calibration.alpha()))
        .append(" | ").append(round(calibration.similarityThreshold()))
        .append(" | ").append(calibration.sampleSize())
        .append(" | ").append(percent(coverage.target()))
        .append(" | ").append(percent(coverage.rate()))
        .append(" | ").append(percent(coverage.standardError()))
        .append(" |\n");
  }

  /** Records a metric the plan asks for that no shipped code can produce yet. */
  void pending(String key, String reason) {
    ObjectNode node = metrics().putObject(key);
    node.putNull("value");
    node.put("pending", reason);
    markdown.append("## ").append(key).append("\n\nNot measurable yet: ")
        .append(reason).append("\n\n");
  }

  void write(Path directory) {
    try {
      Files.createDirectories(directory);
      Files.writeString(directory.resolve("report.json"),
          MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
          StandardCharsets.UTF_8);
      Files.writeString(directory.resolve("report.md"), markdown.toString(),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write the evaluation report", e);
    }
  }

  private ObjectNode metrics() {
    return root.has("metrics") ? (ObjectNode) root.get("metrics") : root.putObject("metrics");
  }

  private void putTiers(ObjectNode node, Map<ModelTier, Integer> counts) {
    counts.forEach((tier, count) -> node.put(tier.name(), count));
  }

  private void putCounts(ObjectNode node, Map<String, Integer> counts) {
    counts.forEach(node::put);
  }

  private void putScores(ObjectNode node, Map<String, RoutingEvaluator.Score> scores) {
    scores.forEach((key, score) -> {
      ObjectNode entry = node.putObject(key);
      entry.put("total", score.total());
      entry.put("correct", score.correct());
      entry.put("accuracy", round(score.accuracy()));
    });
  }

  private void appendScores(String title, Map<String, RoutingEvaluator.Score> scores) {
    if (scores.isEmpty()) {
      return;
    }
    markdown.append("| ").append(title).append(" | n | accuracy |\n|---|---|---|\n");
    scores.forEach((key, score) -> markdown.append("| ").append(key)
        .append(" | ").append(score.total())
        .append(" | ").append(percent(score.accuracy())).append(" |\n"));
    markdown.append('\n');
  }

  private void appendCounts(String title, Map<String, Integer> counts) {
    if (counts.isEmpty()) {
      return;
    }
    markdown.append("| ").append(title).append(" | count |\n|---|---|\n");
    counts.forEach((key, count) ->
        markdown.append("| ").append(key).append(" | ").append(count).append(" |\n"));
    markdown.append('\n');
  }

  private static double round(double value) {
    return Math.round(value * 10000.0) / 10000.0;
  }

  private static String percent(double ratio) {
    return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
  }
}
