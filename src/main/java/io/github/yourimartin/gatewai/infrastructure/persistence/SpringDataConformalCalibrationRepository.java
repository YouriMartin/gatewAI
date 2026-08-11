package io.github.yourimartin.gatewai.infrastructure.persistence;

import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataConformalCalibrationRepository
    extends JpaRepository<ConformalCalibrationEntity, CalibrationTarget> {
}
