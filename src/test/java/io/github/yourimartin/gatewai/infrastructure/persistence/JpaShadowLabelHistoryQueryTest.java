package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.calibration.ShadowLabel;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeVerdict;
import io.github.yourimartin.gatewai.domain.port.out.ShadowLabelHistory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The three queries behind a shadow fit, on real SQL (v4 D.3, ADR 0019): the
 * join of a verdict to the SHADOW hit it grades, the not-sampled count, and the
 * ambiguous sets a new threshold would serve.
 *
 * <p>Every row is written under random client ids and removed afterwards. Needs
 * Postgres, so {@code @Tag("integration")}: run with {@code ./mvnw -Pit test}.
 */
@Tag("integration")
@SpringBootTest(properties = "spring.profiles.active=mock")
class JpaShadowLabelHistoryQueryTest {

  private static final String MODEL = "paraphrase-multilingual-MiniLM-L12-v2";
  private static final Instant AT = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired
  private ShadowLabelHistory history;

  @Autowired
  private JdbcTemplate jdbc;

  private final String client = "test-" + UUID.randomUUID();
  private final String other = "test-" + UUID.randomUUID();
  private final List<String> correlationIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (String id : correlationIds) {
      jdbc.update("DELETE FROM would_hit_assessment WHERE correlation_id = ?", id);
      jdbc.update("DELETE FROM cache_decision WHERE correlation_id = ?", id);
      jdbc.update("DELETE FROM request_log WHERE correlation_id = ?", id);
    }
  }

  @Test
  void aVerdictIsJoinedToTheShadowHitItGradesAndOnlyToThatOne() {
    String graded = assessment(client, "JUDGED", "NO");
    decision(graded, "SHADOW", "HIT", "SINGLETON", 0.95, 0.40, 0.92, MODEL);
    // Graded by the judge, but its only decision is an enforced one: not this join.
    String enforced = assessment(client, "JUDGED", "YES");
    decision(enforced, "ENFORCE", "HIT", "SINGLETON", 0.96, 0.40, 0.92, MODEL);
    String failed = assessment(client, "FAILED", null);
    assessment(client, "NOT_SAMPLED", null);
    assessment(other, "JUDGED", "YES");

    List<ShadowLabel> labels = history.labels(client);

    assertEquals(3, labels.size(), "the not-sampled one is not a label: " + labels);
    assertTrue(labels.contains(new ShadowLabel(client, AT, JudgeOutcome.JUDGED,
        JudgeVerdict.NO, "judge", "would-hit-v1", 0.95, 0.92, MODEL)));
    assertTrue(labels.contains(new ShadowLabel(client, AT, JudgeOutcome.JUDGED,
        JudgeVerdict.YES, "judge", "would-hit-v1", null, null, null)),
        "an enforced decision does not stand in for a shadow one");
    assertTrue(labels.contains(new ShadowLabel(client, AT, JudgeOutcome.FAILED, null,
        "judge", "would-hit-v1", null, null, null)), "failed: " + failed);
    assertEquals(1, history.notSampled(client));
    assertTrue(history.labels(null).size() >= 4, "null reads every client");
    assertFalse(history.labels(other).isEmpty());
  }

  @Test
  void countsTheAmbiguousSetsTheNewThresholdWouldServe() {
    // Floor 0.94, new threshold 0.97: a top at or above 0.97 with a runner-up in
    // [0.94, 0.97) was ambiguous at the floor and is a singleton at 0.97.
    ambiguous(client, 0.98, 0.95, 0.92, MODEL);  // counts
    ambiguous(client, 0.97, 0.94, 0.94, MODEL);  // counts: both bounds included
    ambiguous(client, 0.98, 0.93, 0.92, MODEL);  // runner-up below the floor
    ambiguous(client, 0.98, 0.97, 0.92, MODEL);  // still ambiguous at 0.97
    ambiguous(client, 0.96, 0.95, 0.92, MODEL);  // top below the new threshold
    ambiguous(client, 0.98, 0.95, 0.95, MODEL);  // recorded above the floor
    ambiguous(client, 0.98, 0.95, 0.92, "other-model");
    ambiguous(other, 0.98, 0.95, 0.92, MODEL);   // another client

    assertEquals(2, history.ambiguousBecomingServable(client, MODEL, 0.94, 0.97));
    assertEquals(1, history.ambiguousBecomingServable(other, MODEL, 0.94, 0.97));
  }

  private String assessment(String clientId, String outcome, String verdict) {
    String correlationId = UUID.randomUUID().toString();
    correlationIds.add(correlationId);
    boolean sampled = !"NOT_SAMPLED".equals(outcome);
    jdbc.update("""
        INSERT INTO would_hit_assessment (id, correlation_id, client_id, created_at,
            answer_similarity, beyond_window, embedding_model, judge_outcome,
            judge_verdict, judge_model_id, rubric_version)
        VALUES (?, ?, ?, ?, 0.9, false, ?, ?, ?, ?, ?)""",
        UUID.randomUUID(), correlationId, clientId, Timestamp.from(AT), MODEL, outcome,
        verdict, sampled ? "judge" : null, sampled ? "would-hit-v1" : null);
    return correlationId;
  }

  private void ambiguous(String clientId, double top, double runnerUp, double threshold,
                         String model) {
    String correlationId = UUID.randomUUID().toString();
    correlationIds.add(correlationId);
    decision(correlationId, "SHADOW", "MISS", "AMBIGUOUS", top, runnerUp, threshold, model);
    jdbc.update("""
        INSERT INTO request_log (id, correlation_id, timestamp, model, prompt_hash,
            prompt_tokens, completion_tokens, total_tokens, latency_ms, client_id,
            pricing_basis, currency, mode)
        VALUES (?, ?, ?, 'm', 'h', 1, 1, 2, 1, ?, 'BLENDED', 'EUR', 'SHADOW')""",
        UUID.randomUUID(), correlationId, Timestamp.from(AT), clientId);
  }

  private void decision(String correlationId, String mode, String outcome, String status,
                        double top, double runnerUp, double threshold, String model) {
    jdbc.update("""
        INSERT INTO cache_decision (id, correlation_id, created_at, prompt_hash, outcome,
            similarity_score, runner_up_score, threshold, embedding_model,
            conformal_status, mode)
        VALUES (?, ?, ?, 'h', ?, ?, ?, ?, ?, ?, ?)""",
        UUID.randomUUID(), correlationId, Timestamp.from(AT), outcome, top, runnerUp,
        threshold, model, status, mode);
  }
}
