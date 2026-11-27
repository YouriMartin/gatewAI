package io.github.yourimartin.gatewai.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataConformalCalibrationRepository
    extends JpaRepository<ConformalCalibrationEntity, ConformalCalibrationId> {
}
