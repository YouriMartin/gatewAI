package com.example.gatewai.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRequestLogRepository extends JpaRepository<RequestLogEntity, UUID> {
}
