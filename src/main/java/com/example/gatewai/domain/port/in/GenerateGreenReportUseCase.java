package com.example.gatewai.domain.port.in;

import java.time.Instant;

import com.example.gatewai.domain.model.GreenReport;

/** Produces an aggregated green report over a date range (Phase 4.5). */
public interface GenerateGreenReportUseCase {

  GreenReport generate(Instant from, Instant to);
}
