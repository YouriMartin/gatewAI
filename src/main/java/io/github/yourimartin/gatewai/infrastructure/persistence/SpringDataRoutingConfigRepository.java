package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataRoutingConfigRepository
    extends JpaRepository<RoutingConfigEntity, Integer> {

  /**
   * The row, locked for the caller's transaction. Concurrent admin edits then
   * serialize instead of racing on a read-modify-write, which is what keeps
   * {@code revision} monotonic and keeps a column-scoped write from reading a
   * value another node is in the middle of replacing.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from RoutingConfigEntity c where c.id = "
      + RoutingConfigEntity.ROW_ID)
  Optional<RoutingConfigEntity> findForUpdate();

  /**
   * Inserts the first row, doing nothing if another node got there first.
   *
   * <p>Native rather than {@code save()} on purpose. A JPA merge of an entity
   * with an assigned id turns into an update when the row exists, so the loser
   * of a concurrent first start would silently overwrite the winner's
   * configuration with its own defaults — the exact divergence this batch
   * removes. {@code ON CONFLICT DO NOTHING} makes losing the race a no-op, in
   * one statement, with no exception to catch.
   *
   * @return 1 when this call inserted the row, 0 when one already existed
   */
  @Modifying
  @Query(value = """
      INSERT INTO routing_config (
          id, revision, strategy, entry_length_threshold,
          premium_length_threshold, premium_keywords,
          route_similarity_threshold, routes, cascade_margin_band, updated_at)
      VALUES (
          1, 1, :strategy, :entryLengthThreshold,
          :premiumLengthThreshold, cast(:premiumKeywords as jsonb),
          :routeSimilarityThreshold, cast(:routes as jsonb),
          :cascadeMarginBand, :updatedAt)
      ON CONFLICT (id) DO NOTHING
      """, nativeQuery = true)
  int insertIfAbsent(@Param("strategy") String strategy,
                     @Param("entryLengthThreshold") int entryLengthThreshold,
                     @Param("premiumLengthThreshold") int premiumLengthThreshold,
                     @Param("premiumKeywords") String premiumKeywords,
                     @Param("routeSimilarityThreshold")
                     double routeSimilarityThreshold,
                     @Param("routes") String routes,
                     @Param("cascadeMarginBand") double cascadeMarginBand,
                     @Param("updatedAt") Instant updatedAt);
}
