package com.example.gatewai.infrastructure.persistence;

import com.example.gatewai.domain.model.RequestLog;
import com.example.gatewai.domain.port.out.RequestLogRepository;

import org.springframework.stereotype.Component;

@Component
class JpaRequestLogAdapter implements RequestLogRepository {

  private final SpringDataRequestLogRepository jpaRepository;

  JpaRequestLogAdapter(SpringDataRequestLogRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public void save(RequestLog log) {
    jpaRepository.save(new RequestLogEntity(log));
  }
}
