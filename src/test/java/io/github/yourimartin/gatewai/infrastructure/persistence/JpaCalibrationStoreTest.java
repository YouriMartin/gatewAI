package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationSource;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalGuarantee;
import io.github.yourimartin.gatewai.domain.model.calibration.ShadowFit;
import io.github.yourimartin.gatewai.domain.port.out.CalibrationStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One calibration per (target, client) on real SQL (V16, v4 D.3, ADR 0019): the
 * key, the round trip of a shadow fit, and the CHECK constraints that hold the
 * table to the ADR even when the code is bypassed.
 *
 * <p>Writes client rows only, under a random client id, so a developer's global
 * calibration is never touched. Needs Postgres, so {@code @Tag("integration")}:
 * run with {@code ./mvnw -Pit test}.
 */
@Tag("integration")
@SpringBootTest(properties = "spring.profiles.active=mock")
class JpaCalibrationStoreTest {

  private static final Instant WHEN = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired
  private CalibrationStore store;

  @Autowired
  private JdbcTemplate jdbc;

  private final String clientId = "test-" + UUID.randomUUID();

  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM conformal_calibration WHERE client_id = ?", clientId);
  }

  @Test
  void aClientRowRoundTripsBesideTheGlobalOneAndIsOverwrittenInPlace() {
    Optional<ConformalCalibration> globalBefore = store.find(CalibrationTarget.CACHE, null);

    store.save(clientRow(0.97, 50));
    store.save(clientRow(0.98, 60));

    ConformalCalibration stored = store.find(CalibrationTarget.CACHE, clientId).orElseThrow();
    assertEquals(clientRow(0.98, 60), stored);
    assertEquals(1, rowsOfTheClient(), "a recalibration overwrites its own scope");
    assertEquals(globalBefore, store.find(CalibrationTarget.CACHE, null),
        "a client fit leaves the global row as it was");
    assertTrue(store.findAll().contains(stored));
  }

  @Test
  void deletingAClientRowRemovesItAndOnlyIt() {
    Optional<ConformalCalibration> globalBefore = store.find(CalibrationTarget.CACHE, null);
    store.save(clientRow(0.97, 50));

    assertTrue(store.delete(CalibrationTarget.CACHE, clientId));
    assertFalse(store.delete(CalibrationTarget.CACHE, clientId), "nothing left to remove");

    assertEquals(Optional.empty(), store.find(CalibrationTarget.CACHE, clientId));
    assertEquals(globalBefore, store.find(CalibrationTarget.CACHE, null));
  }

  @Test
  void theGlobalRowCannotBeDeleted() {
    assertThrows(IllegalArgumentException.class,
        () -> store.delete(CalibrationTarget.CACHE, null));
    assertThrows(IllegalArgumentException.class,
        () -> store.delete(CalibrationTarget.CACHE, ""));
  }

  @Test
  void theTableRefusesARoutingRowForAClient() {
    assertThrows(DataIntegrityViolationException.class, () -> insert("ROUTING",
        "CORRECT_TARGET_COVERAGE", clientId, "SHADOW", "judge", "would-hit-v1", 0.5));
  }

  @Test
  void theTableRefusesAClientRowFromTheLabelledSet() {
    assertThrows(DataIntegrityViolationException.class, () -> insert("CACHE",
        "WRONG_ANSWER_RATE", clientId, "LABELLED_SET", null, null, null));
  }

  @Test
  void theTableRefusesAShadowRowWithoutItsProvenanceAndALabelledRowWithOne() {
    assertThrows(DataIntegrityViolationException.class, () -> insert("CACHE",
        "WRONG_ANSWER_RATE", clientId, "SHADOW", "judge", null, 0.95));
    assertThrows(DataIntegrityViolationException.class, () -> insert("CACHE",
        "WRONG_ANSWER_RATE", clientId, "SHADOW", null, "would-hit-v1", 0.95));
    assertThrows(DataIntegrityViolationException.class, () -> insert("CACHE",
        "WRONG_ANSWER_RATE", clientId, "SHADOW", "judge", "would-hit-v1", null));
    assertEquals(0, rowsOfTheClient());
  }

  @Test
  void theTableRefusesAnUnknownSource() {
    assertThrows(DataIntegrityViolationException.class, () -> insert("CACHE",
        "WRONG_ANSWER_RATE", clientId, "GUESSED", "judge", "would-hit-v1", 0.95));
  }

  private ConformalCalibration clientRow(double qhat, int negatives) {
    return new ConformalCalibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, qhat, negatives,
        "paraphrase-multilingual-MiniLM-L12-v2", null, WHEN, clientId,
        CalibrationSource.SHADOW, new ShadowFit("judge", "would-hit-v1", 0.95));
  }

  private int rowsOfTheClient() {
    Integer rows = jdbc.queryForObject(
        "SELECT count(*) FROM conformal_calibration WHERE client_id = ?", Integer.class,
        clientId);
    return rows == null ? 0 : rows;
  }

  private void insert(String target, String guarantee, String client, String source,
                      String judgeModel, String rubricVersion, Double labelFloor) {
    jdbc.update("""
        INSERT INTO conformal_calibration (target, client_id, guarantee, alpha, q_hat,
            sample_size, embedding_model, calibrated_at, source, judge_model,
            rubric_version, label_floor)
        VALUES (?, ?, ?, 0.10, 0.95, 50, 'm', now(), ?, ?, ?, ?)""",
        target, client, guarantee, source, judgeModel, rubricVersion, labelFloor);
  }
}
