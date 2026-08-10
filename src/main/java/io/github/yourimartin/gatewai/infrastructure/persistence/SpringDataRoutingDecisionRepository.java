package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataRoutingDecisionRepository
    extends JpaRepository<RoutingDecisionEntity, UUID> {

  @Modifying
  @Query("delete from RoutingDecisionEntity d where d.createdAt < :cutoff")
  int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
