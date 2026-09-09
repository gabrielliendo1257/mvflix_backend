package com.gcorp.service.app.mvflix_media_ingestion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

class RecoveryServiceTest {
  @Test
  void backoffUsesInitialIntervalThenGrowsAndCaps() {
    var f = fixture(MediaIngestion.Phase.RECONCILIATION_REQUIRED,
        Duration.ofSeconds(1), Duration.ofSeconds(5), 0);

    assertThat(f.service.backoffSeconds(0)).isEqualTo(1);
    assertThat(f.service.backoffSeconds(1)).isEqualTo(2);
    assertThat(f.service.backoffSeconds(2)).isEqualTo(4);
    assertThat(f.service.backoffSeconds(3)).isEqualTo(5);
  }

  @Test
  void backoffRejectsInvalidConfiguration() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        fixture(MediaIngestion.Phase.RECONCILIATION_REQUIRED,
            Duration.ZERO, Duration.ofSeconds(5), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void completesWhenBothAuthoritiesAreAlreadyComplete() {
    var f = fixture(MediaIngestion.Phase.RECONCILIATION_REQUIRED);
    when(f.clients.catalogStatus(3L, "actor"))
        .thenReturn(Mono.just(new DownstreamClients.CatalogStatus("READY")));
    when(f.clients.storageStatus("9", "actor"))
        .thenReturn(Mono.just(new DownstreamClients.StorageStatus("COMPLETED", 9L, "object")));
    when(f.repository.compareAndSet(any(), any())).thenReturn(Mono.just(true));
    when(f.repository.find(f.ingestion.ingestionId())).thenReturn(Mono.just(f.ingestion));
    when(f.outbox.completed(any())).thenReturn(Mono.empty());

    f.service.recover(f.ingestion).block();

    verify(f.repository)
        .compareAndSet(eq(f.ingestion), argThat(i -> i.storageId().equals(9L)
            && i.storageKey().equals("object")));
    verify(f.repository)
        .compareAndSet(argThat(i -> Long.valueOf(9L).equals(i.storageId())),
            argThat(i -> i.phase() == MediaIngestion.Phase.COMPLETED));
    verify(f.outbox).completed(any());
    verify(f.clients, never()).completeCatalog(anyLong(), any(), anyLong(), any());
  }

  @Test
  void derivesStorageObjectIdFromUploadIdWhenStorageStatusOmitsIt() {
    var f = fixture(MediaIngestion.Phase.FINALIZING_CATALOG);
    when(f.clients.catalogStatus(3L, "actor"))
        .thenReturn(Mono.just(new DownstreamClients.CatalogStatus("DRAFT")));
    when(f.clients.storageStatus("9", "actor"))
        .thenReturn(Mono.just(new DownstreamClients.StorageStatus("COMPLETED", null, null)));
    when(f.repository.compareAndSet(eq(f.ingestion), any())).thenReturn(Mono.just(true));
    when(f.repository.find(f.ingestion.ingestionId())).thenReturn(Mono.just(f.ingestion));
    when(f.clients.completeCatalog(3L, "object", 9L, "actor")).thenReturn(Mono.empty());

    f.service.recover(f.ingestion).block();

    verify(f.clients).completeCatalog(3L, "object", 9L, "actor");
    verifyNoInteractions(f.compensations);
  }

  @Test
  void completedUploadNeedsReconciliationWhenCatalogFinalizationFails() {
    var f = fixture(MediaIngestion.Phase.PREPARING_UPLOAD);
    when(f.clients.storageStatus("9", "actor"))
        .thenReturn(Mono.just(new DownstreamClients.StorageStatus("COMPLETED", 9L, "object")));
    when(f.repository.compareAndSet(any(), any())).thenReturn(Mono.just(true));
    when(f.repository.find(f.ingestion.ingestionId())).thenReturn(Mono.just(f.ingestion));
    when(f.clients.completeCatalog(3L, "object", 9L, "actor"))
        .thenReturn(Mono.error(new IllegalStateException("Movies unavailable")));

    f.service.recover(f.ingestion).block();

    verify(f.repository)
        .compareAndSet(any(), argThat(i -> i.phase() == MediaIngestion.Phase.RECONCILIATION_REQUIRED));
    verifyNoInteractions(f.compensations);
    verifyNoInteractions(f.outbox);
  }

  @Test
  void reloadsPersistedStateWhenClaimCasLoses() {
    var f = fixture(MediaIngestion.Phase.PREPARING_UPLOAD);
    var completed = completed(f.ingestion);
    when(f.clients.storageStatus("9", "actor"))
        .thenReturn(Mono.just(new DownstreamClients.StorageStatus("COMPLETED", 9L, "object")));
    when(f.repository.compareAndSet(eq(f.ingestion), any())).thenReturn(Mono.just(false));
    when(f.repository.find(f.ingestion.ingestionId())).thenReturn(Mono.just(completed));

    var result = f.service.recover(f.ingestion).block();

    org.assertj.core.api.Assertions.assertThat(result.phase())
        .isEqualTo(MediaIngestion.Phase.COMPLETED);
    verify(f.repository).find(f.ingestion.ingestionId());
    verifyNoInteractions(f.compensations, f.outbox);
  }

  @Test
  void reloadsPersistedStateWhenFinalCasLoses() {
    var f = fixture(MediaIngestion.Phase.PREPARING_UPLOAD);
    var completed = completed(f.ingestion);
    when(f.clients.storageStatus("9", "actor"))
        .thenReturn(Mono.just(new DownstreamClients.StorageStatus("COMPLETED", 9L, "object")));
    when(f.repository.compareAndSet(any(), any()))
        .thenReturn(Mono.just(true), Mono.just(false));
    when(f.repository.find(f.ingestion.ingestionId())).thenReturn(Mono.just(completed));
    when(f.clients.completeCatalog(3L, "object", 9L, "actor")).thenReturn(Mono.empty());

    var result = f.service.recover(f.ingestion).block();

    org.assertj.core.api.Assertions.assertThat(result.phase())
        .isEqualTo(MediaIngestion.Phase.COMPLETED);
    verify(f.repository).find(f.ingestion.ingestionId());
    verifyNoInteractions(f.compensations);
  }

  @Test
  void earlyPhaseIsFailedWithoutRecreatingDraft() {
    var f = fixture(MediaIngestion.Phase.PREPARING_CATALOG);
    when(f.repository.compareAndSet(eq(f.ingestion), any())).thenReturn(Mono.just(true));
    when(f.repository.find(f.ingestion.ingestionId())).thenReturn(Mono.just(f.ingestion));
    when(f.outbox.failed(any())).thenReturn(Mono.empty());

    f.service.recover(f.ingestion).block();

    verify(f.repository)
        .compareAndSet(eq(f.ingestion), argThat(i -> i.phase() == MediaIngestion.Phase.FAILED));
    verifyNoInteractions(f.clients);
  }

  private static Fixture fixture(MediaIngestion.Phase phase) {
    return fixture(phase, Duration.ofSeconds(30), Duration.ofHours(1), 0);
  }

  private static Fixture fixture(MediaIngestion.Phase phase, Duration initial,
      Duration maximum, double jitter) {
    var repository = mock(MediaIngestionRepository.class);
    var clients = mock(DownstreamClients.class);
    var outbox = mock(Outbox.class);
    var compensations = mock(CompensationRepository.class);
    var transactions = mock(TransactionalOperator.class);
    when(transactions.transactional(any(Mono.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var now = Instant.now();
    var ingestion =
        new MediaIngestion(
            UUID.randomUUID(),
            "actor",
            phase == MediaIngestion.Phase.PREPARING_CATALOG ? null : 3L,
            phase == MediaIngestion.Phase.PREPARING_CATALOG ? null : "9",
            phase,
            null,
            1,
            0,
            now,
            now,
            now,
            "key",
            "file",
            10,
            "video/mp4",
            null,
            null,
            "object");
    return new Fixture(
        repository,
        clients,
        outbox,
        compensations,
        new RecoveryService(repository, clients, outbox, compensations, transactions,
            initial, maximum, jitter),
        ingestion);
  }

  private static MediaIngestion completed(MediaIngestion source) {
    return new MediaIngestion(
        source.ingestionId(), source.actorId(), source.catalogItemId(), source.uploadId(),
        MediaIngestion.Phase.COMPLETED, null, source.version() + 2, source.retryCount(),
        source.createdAt(), Instant.now(), source.nextAttemptAt(), source.idempotencyKey(),
        source.fileName(), source.fileSize(), source.mimeType(), source.uploadUrl(),
        9L, "object", source.requestFingerprint(), source.causationId());
  }

  private record Fixture(
      MediaIngestionRepository repository,
      DownstreamClients clients,
      Outbox outbox,
      CompensationRepository compensations,
      RecoveryService service,
      MediaIngestion ingestion) {}
}
