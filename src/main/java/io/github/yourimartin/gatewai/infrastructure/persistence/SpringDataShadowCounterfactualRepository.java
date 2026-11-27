package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code shadow_counterfactual} (v4 D.4), purged with the decision tables. */
interface SpringDataShadowCounterfactualRepository
    extends JpaRepository<ShadowCounterfactualEntity, UUID> {

  @Modifying
  @Query("delete from ShadowCounterfactualEntity e where e.createdAt < :cutoff")
  int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
