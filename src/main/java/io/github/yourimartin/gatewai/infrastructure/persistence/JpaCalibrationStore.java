package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.port.out.CalibrationStore;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for {@link CalibrationStore} (v2 batch 3). */
@Component
class JpaCalibrationStore implements CalibrationStore {

  private final SpringDataConformalCalibrationRepository repository;

  JpaCalibrationStore(SpringDataConformalCalibrationRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ConformalCalibration> find(CalibrationTarget target) {
    return repository.findById(target).map(ConformalCalibrationEntity::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConformalCalibration> findAll() {
    return repository.findAll().stream()
        .map(ConformalCalibrationEntity::toDomain)
        .toList();
  }

  @Override
  @Transactional
  public void save(ConformalCalibration calibration) {
    repository.save(new ConformalCalibrationEntity(calibration));
  }
}
