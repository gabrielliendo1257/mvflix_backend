package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.dao.DataIntegrityViolationException;

import com.gcorp.service.app.mvflix_media_ingestion.application.CompensationRepository;
import com.gcorp.service.app.mvflix_media_ingestion.application.DownstreamClients;
import com.gcorp.service.app.mvflix_media_ingestion.application.MediaIngestionRepository;
import com.gcorp.service.app.mvflix_media_ingestion.application.Outbox;
import com.gcorp.service.app.mvflix_media_ingestion.application.RecoveryService;
import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SpringBootTest(properties = {
    "spring.main.web-application-type=reactive",
    "mvflix.messaging.kafka.enabled=false",
    "mvflix.compensation.enabled=false",
    "mvflix.recovery.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RecoveryConcurrencyIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.r2dbc.url", () ->
        "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
            + "/" + POSTGRES.getDatabaseName());
    registry.add("spring.r2dbc.username", POSTGRES::getUsername);
    registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    registry.add("spring.datasource.url", () ->
        "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
            + "/" + POSTGRES.getDatabaseName());
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", () ->
        "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
            + "/" + POSTGRES.getDatabaseName());
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired MediaIngestionRepository repository;
  @Autowired RecoveryService recovery;
  @Autowired DatabaseClient database;

  @MockBean DownstreamClients clients;
  @MockBean Outbox outbox;
  @MockBean CompensationRepository compensations;
  @MockBean ReactiveJwtDecoder jwtDecoder;

  @AfterEach
  void cleanDatabase() {
    database.sql("DELETE FROM media_ingestions").fetch().rowsUpdated().block();
  }

  @Test
  void onlyOneWorkerRecoversClaimedUploadAndPublishesCompletion() {
    UUID ingestionId = UUID.randomUUID();
    Instant now = Instant.now();
    var ingestion = new MediaIngestion(
        ingestionId, "actor", 3L, "upload-9", MediaIngestion.Phase.RECONCILIATION_REQUIRED,
        null, 1, 0, now.minusSeconds(120), now.minusSeconds(120), now.minusSeconds(60),
        "key", "file.mp4", 10L, "video/mp4", null, 9L, "object-key", "fingerprint", null);
    repository.insert(ingestion).block();

    when(clients.catalogStatus(anyLong(), any()))
        .thenReturn(Mono.just(new DownstreamClients.CatalogStatus("DRAFT")));
    when(clients.storageStatus(any(), any()))
        .thenReturn(Mono.just(new DownstreamClients.StorageStatus("COMPLETED", 9L, "object-key")));
    when(clients.completeCatalog(anyLong(), anyString(), anyLong(), anyString()))
        .thenReturn(Mono.empty());
    when(outbox.completed(any())).thenReturn(Mono.empty());
    database.sql("UPDATE media_ingestions SET next_attempt_at=now() - interval '1 minute', recovery_claimed_until=NULL")
        .fetch().rowsUpdated().block();

    var claimed = Flux.merge(
            repository.claimDueRecoverable(1, Duration.ofSeconds(30))
                .subscribeOn(Schedulers.boundedElastic()),
            repository.claimDueRecoverable(1, Duration.ofSeconds(30))
                .subscribeOn(Schedulers.boundedElastic()))
        .collectList()
        .block();
    org.assertj.core.api.Assertions.assertThat(claimed).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(claimed.get(0).retryCount()).isZero();

    Flux.fromIterable(claimed).flatMap(recovery::recover).collectList().block();

    verify(clients, times(1)).completeCatalog(3L, "object-key", 9L, "actor");
    verify(outbox, times(1)).completed(any());
    verifyNoInteractions(compensations);
    var persisted = repository.find(ingestionId).block();
    org.assertj.core.api.Assertions.assertThat(persisted.phase())
        .isEqualTo(MediaIngestion.Phase.COMPLETED);
  }

  @Test
  void earlyPhaseIsNotClaimedBeforeNextAttemptAt() {
    UUID ingestionId = UUID.randomUUID();
    Instant now = Instant.now();
    var ingestion = new MediaIngestion(
        ingestionId, "actor", null, null, MediaIngestion.Phase.STARTING,
        null, 1, 2, now.minusSeconds(120), now.minusSeconds(120), now.plusSeconds(30),
        "key", "file.mp4", 10L, "video/mp4", null);
    repository.insert(ingestion).block();
    database.sql("UPDATE media_ingestions SET retry_count=2")
        .fetch().rowsUpdated().block();

    org.assertj.core.api.Assertions.assertThat(
        repository.claimDueRecoverable(1, Duration.ofSeconds(30)).collectList().block())
        .isEmpty();

    database.sql("UPDATE media_ingestions SET next_attempt_at=now() - interval '1 second'")
        .fetch().rowsUpdated().block();
    var claimed = repository.claimDueRecoverable(1, Duration.ofSeconds(30)).collectList().block();

    org.assertj.core.api.Assertions.assertThat(claimed).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(claimed.get(0).retryCount()).isEqualTo(2);
  }

  @Test
  void storageIdentityIsUniqueAcrossIngestions() {
    Instant now = Instant.now();
    var first = new MediaIngestion(
        UUID.randomUUID(), "actor", 3L, "upload-1", MediaIngestion.Phase.RECONCILIATION_REQUIRED,
        null, 1, 0, now, now, now, "key-1", "one.mp4", 1L, "video/mp4", null, 9L,
        "object-one", "fingerprint-1", null);
    var second = new MediaIngestion(
        UUID.randomUUID(), "actor", 4L, "upload-2", MediaIngestion.Phase.RECONCILIATION_REQUIRED,
        null, 1, 0, now, now, now, "key-2", "two.mp4", 1L, "video/mp4", null, 9L,
        "object-two", "fingerprint-2", null);
    repository.insert(first).block();

    assertThatThrownBy(() -> repository.insert(second).block())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
