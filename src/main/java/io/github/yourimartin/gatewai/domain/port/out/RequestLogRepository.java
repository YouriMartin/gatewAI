package io.github.yourimartin.gatewai.domain.port.out;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.RequestLog;

public interface RequestLogRepository {

  void save(RequestLog log);

  List<RequestLog> findBetween(Instant from, Instant to);
}
