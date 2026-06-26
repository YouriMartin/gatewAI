package com.example.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import com.example.gatewai.domain.model.RequestLog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaRequestLogAdapterTest {

  @Mock
  private SpringDataRequestLogRepository jpaRepository;

  @InjectMocks
  private JpaRequestLogAdapter adapter;

  @Captor
  private ArgumentCaptor<RequestLogEntity> entityCaptor;

  @Test
  void saveDelegatesToSpringData() {
    RequestLog log = new RequestLog(
        UUID.randomUUID(), Instant.now(), "claude-3",
        "a".repeat(64), 10, 5, 15, 200L
    );

    when(jpaRepository.save(any(RequestLogEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    adapter.save(log);

    verify(jpaRepository).save(entityCaptor.capture());
    RequestLogEntity captured = entityCaptor.getValue();
    assertEquals(log.id(), captured.toDomain().id());
    assertEquals(log.model(), captured.toDomain().model());
  }

  @Test
  void roundTripDomainToEntityToDomain() {
    RequestLog original = new RequestLog(
        UUID.randomUUID(), Instant.parse("2026-06-01T12:00:00Z"),
        "claude-3-opus", "b".repeat(64),
        100, 50, 150, 1234L
    );

    RequestLogEntity entity = new RequestLogEntity(original);
    RequestLog restored = entity.toDomain();

    assertEquals(original, restored);
  }
}
