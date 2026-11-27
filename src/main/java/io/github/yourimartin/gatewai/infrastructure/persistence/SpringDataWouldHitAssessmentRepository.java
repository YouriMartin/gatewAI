package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataWouldHitAssessmentRepository
    extends JpaRepository<WouldHitAssessmentEntity, UUID> {

  @Modifying
  @Query("delete from WouldHitAssessmentEntity e where e.createdAt < :cutoff")
  int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);

  /**
   * One row per client, judge model and rubric over {@code [from, to)}: client,
   * judge, rubric, would-hits, mean similarity, beyond window, YES, NO, UNSURE,
   * failed. Aggregated in the database so a long period never loads its rows.
   */
  @Query(nativeQuery = true, value = """
      SELECT client_id, judge_model_id, rubric_version,
             count(*),
             avg(answer_similarity),
             count(*) FILTER (WHERE beyond_window),
             count(*) FILTER (WHERE judge_verdict = 'YES'),
             count(*) FILTER (WHERE judge_verdict = 'NO'),
             count(*) FILTER (WHERE judge_verdict = 'UNSURE'),
             count(*) FILTER (WHERE judge_outcome = 'FAILED')
        FROM would_hit_assessment
       WHERE created_at >= :from AND created_at < :to
       GROUP BY client_id, judge_model_id, rubric_version
       ORDER BY client_id, judge_model_id NULLS FIRST, rubric_version""")
  List<Object[]> countsBetween(@Param("from") Instant from, @Param("to") Instant to);

  /**
   * The would-hits that went to the judge, one row each, with the SHADOW hit they
   * grade (v4 D.3): client, assessed at, judge outcome, verdict, judge model,
   * rubric, similarity, recorded threshold, embedding model. The decision columns
   * are null when its row is gone, which the fit counts rather than hides.
   */
  @Query(nativeQuery = true, value = """
      SELECT w.client_id, w.created_at, w.judge_outcome, w.judge_verdict,
             w.judge_model_id, w.rubric_version,
             d.similarity_score, d.threshold, d.embedding_model
        FROM would_hit_assessment w
        LEFT JOIN cache_decision d
               ON d.correlation_id = w.correlation_id
              AND d.mode = 'SHADOW' AND d.outcome = 'HIT'
       WHERE w.judge_outcome <> 'NOT_SAMPLED'
         AND (CAST(:client AS varchar) IS NULL OR w.client_id = :client)
       ORDER BY w.created_at""")
  List<Object[]> shadowLabels(@Param("client") String clientId);

  /** The would-hits the judge never saw, for one client or all (v4 D.3). */
  @Query(nativeQuery = true, value = """
      SELECT count(*)
        FROM would_hit_assessment
       WHERE judge_outcome = 'NOT_SAMPLED'
         AND (CAST(:client AS varchar) IS NULL OR client_id = :client)""")
  long countNotSampled(@Param("client") String clientId);

  /**
   * SHADOW decisions ambiguous at the floor that a singleton at {@code qhat}
   * would serve (v4 D.3, ADR 0019): top score at or above {@code qhat}, runner-up
   * in [floor, qhat). The client comes from the request's {@code request_log} row,
   * since {@code cache_decision} has none.
   */
  @Query(nativeQuery = true, value = """
      SELECT count(*)
        FROM cache_decision d
        JOIN request_log r ON r.correlation_id = d.correlation_id
       WHERE d.mode = 'SHADOW'
         AND d.conformal_status = 'AMBIGUOUS'
         AND d.embedding_model = :model
         AND d.threshold <= :floor
         AND d.similarity_score >= :qhat
         AND d.runner_up_score >= :floor AND d.runner_up_score < :qhat
         AND (CAST(:client AS varchar) IS NULL OR r.client_id = :client)""")
  long countAmbiguousBecomingServable(@Param("client") String clientId,
                                      @Param("model") String embeddingModel,
                                      @Param("floor") double floor,
                                      @Param("qhat") double qhat);
}
