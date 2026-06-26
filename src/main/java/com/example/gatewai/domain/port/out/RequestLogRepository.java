package com.example.gatewai.domain.port.out;

import com.example.gatewai.domain.model.RequestLog;

public interface RequestLogRepository {

  void save(RequestLog log);
}
