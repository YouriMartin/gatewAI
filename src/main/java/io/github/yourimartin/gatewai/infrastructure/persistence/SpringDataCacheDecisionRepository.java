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

interface SpringDataCacheDecisionRepository
    extends JpaRepository<CacheDecisionEntity, UUID> {

  @Modifying
  @Query("delete from CacheDecisionEntity d where d.createdAt < :cutoff")
  int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);

  Optional<CacheDecisionEntity> findFirstByCorrelationIdOrderByCreatedAtDesc(
      String correlationId);

  List<CacheDecisionEntity> findAllByOrderByCreatedAtDesc(Pageable page);

  List<CacheDecisionEntity> findByCorrelationIdInOrderByCreatedAtDesc(
      List<String> correlationIds);
}
