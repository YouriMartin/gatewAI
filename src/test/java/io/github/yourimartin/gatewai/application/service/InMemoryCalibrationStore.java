package io.github.yourimartin.gatewai.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.port.out.CalibrationStore;

/** A calibration store with no database behind it, so the fit is what is under test. */
final class InMemoryCalibrationStore implements CalibrationStore {

  private final List<ConformalCalibration> saved = new ArrayList<>();

  @Override
  public Optional<ConformalCalibration> find(CalibrationTarget target, String clientId) {
    return saved.stream().filter(c -> sameScope(c, target, clientId)).findFirst();
  }

  @Override
  public List<ConformalCalibration> findAll() {
    return List.copyOf(saved);
  }

  @Override
  public void save(ConformalCalibration calibration) {
    saved.removeIf(c -> sameScope(c, calibration.target(), calibration.clientId()));
    saved.add(calibration);
  }

  @Override
  public boolean delete(CalibrationTarget target, String clientId) {
    return saved.removeIf(c -> sameScope(c, target, clientId));
  }

  private static boolean sameScope(ConformalCalibration c, CalibrationTarget target,
                                   String clientId) {
    return c.target() == target && Objects.equals(c.clientId(), clientId);
  }
}
