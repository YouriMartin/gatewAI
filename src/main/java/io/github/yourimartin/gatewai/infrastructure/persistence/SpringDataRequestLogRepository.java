package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRequestLogRepository
    extends JpaRepository<RequestLogEntity, UUID> {

  List<RequestLogEntity> findByTimestampBetween(Instant from, Instant to);
}
