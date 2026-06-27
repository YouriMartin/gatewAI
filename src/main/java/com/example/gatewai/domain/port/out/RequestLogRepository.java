package com.example.gatewai.domain.port.out;

import java.time.Instant;
import java.util.List;

import com.example.gatewai.domain.model.RequestLog;

public interface RequestLogRepository {

  void save(RequestLog log);

  List<RequestLog> findBetween(Instant from, Instant to);
}
