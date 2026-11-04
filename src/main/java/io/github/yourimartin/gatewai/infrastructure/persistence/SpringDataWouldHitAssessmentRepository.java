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
}
