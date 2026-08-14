package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataRoutingDecisionRepository
    extends JpaRepository<RoutingDecisionEntity, UUID> {

  @Modifying
  @Query("delete from RoutingDecisionEntity d where d.createdAt < :cutoff")
  int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);

  /**
   * Newest first, because a retried correlation id can carry more than one
   * routing decision and the explanation is about the last one taken.
   */
  Optional<RoutingDecisionEntity> findFirstByCorrelationIdOrderByCreatedAtDesc(
      String correlationId);

  List<RoutingDecisionEntity> findAllByOrderByCreatedAtDesc(Pageable page);

  List<RoutingDecisionEntity> findByCorrelationIdInOrderByCreatedAtDesc(
      List<String> correlationIds);
}
