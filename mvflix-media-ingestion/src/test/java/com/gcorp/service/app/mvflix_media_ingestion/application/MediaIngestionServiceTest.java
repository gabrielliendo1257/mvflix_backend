package com.gcorp.service.app.mvflix_media_ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

class MediaIngestionServiceTest {
  @Test
  void createRejectsUserThatIsNotEligibleBeforeCreatingIngestion() {
    var fixture = fixture(MediaIngestion.Phase.STARTING);
    when(fixture.repo.findByKey("a", "k")).thenReturn(Mono.empty());
    when(fixture.clients.mediaIngestionEligibility("a"))
        .thenReturn(Mono.just(new DownstreamClients.MediaIngestionEligibility(false)));

    assertThrows(
        IllegalStateException.class,
        () -> fixture.service.create("a", "k", Map.of(), "x", 1, "m").block());
    verify(fixture.repo).findByKey("a", "k");
    verify(fixture.clients).mediaIngestionEligibility("a");
    verify(fixture.repo, never()).insert(any());
  }

  @Test
  void createFailsClosedWhenEligibilityIsUnavailable() {
    var fixture = fixture(MediaIngestion.Phase.STARTING);
    when(fixture.repo.findByKey("a", "k")).thenReturn(Mono.empty());
    when(fixture.clients.mediaIngestionEligibility("a")).thenReturn(Mono.empty());

    assertThrows(
        IllegalStateException.class,
        () -> fixture.service.create("a", "k", Map.of(), "x", 1, "m").block());
    verify(fixture.repo, never()).insert(any());
  }

  @Test
  void createReplaysExistingIngestionBeforeCheckingEligibility() {
    var fixture = fixture(MediaIngestion.Phase.AWAITING_UPLOAD);
    var existing = new MediaIngestion(
        fixture.ingestion.ingestionId(), fixture.ingestion.actorId(), fixture.ingestion.catalogItemId(),
        fixture.ingestion.uploadId(), fixture.ingestion.phase(), fixture.ingestion.failureCode(),
        fixture.ingestion.version(), fixture.ingestion.retryCount(), fixture.ingestion.createdAt(),
        fixture.ingestion.updatedAt(), fixture.ingestion.nextAttemptAt(), fixture.ingestion.idempotencyKey(),
        fixture.ingestion.fileName(), fixture.ingestion.fileSize(), fixture.ingestion.mimeType(),
        fixture.ingestion.uploadUrl(), fixture.ingestion.storageId(), fixture.ingestion.storageKey(),
        MediaIngestionService.fingerprint(Map.of(), "x", 1, "m"), fixture.ingestion.causationId());
    when(fixture.repo.findByKey("a", "k")).thenReturn(Mono.just(existing));

    assertThat(fixture.service.create("a", "k", Map.of(), "x", 1, "m").block())
        .isEqualTo(existing);
    verify(fixture.clients, never()).mediaIngestionEligibility(any());
    verify(fixture.repo, never()).insert(any());
  }

  @Test
  void completeRequestsStorageAndKeepsIngestionAwaitingUpload() {
    var fixture = fixture(MediaIngestion.Phase.AWAITING_UPLOAD);
    when(fixture.repo.compareAndSet(eq(fixture.ingestion), any())).thenReturn(Mono.just(true));
    when(fixture.clients.requestUploadCompletion(
            "upload", "a", fixture.ingestion.ingestionId() + ":complete-upload"))
        .thenReturn(Mono.empty());
    when(fixture.repo.find(fixture.ingestion.ingestionId()))
        .thenReturn(Mono.just(fixture.ingestion));

    assertThat(fixture.service.complete(fixture.ingestion.ingestionId(), "a", 9L).block().phase())
        .isEqualTo(MediaIngestion.Phase.AWAITING_UPLOAD);
    verify(fixture.clients)
        .requestUploadCompletion(
            "upload", "a", fixture.ingestion.ingestionId() + ":complete-upload");
    verifyNoInteractions(fixture.outbox);
  }

  @Test
  void completeIsIdempotentWhenAlreadyCompleted() {
    var fixture = fixture(MediaIngestion.Phase.COMPLETED);
    when(fixture.repo.find(fixture.ingestion.ingestionId()))
        .thenReturn(Mono.just(fixture.ingestion));

    assertThat(fixture.service.complete(fixture.ingestion.ingestionId(), "a", null).block())
        .isEqualTo(fixture.ingestion);
    verifyNoInteractions(fixture.clients);
  }

  @Test
  void uploadCompletedIsIdempotentWhenReconciliationAlreadyHasStorageIdentity() {
    var fixture = fixture(MediaIngestion.Phase.RECONCILIATION_REQUIRED);
    var enriched = fixture.ingestion.withStorageIdentity(9L, "object");
    when(fixture.repo.find(fixture.ingestion.ingestionId())).thenReturn(Mono.just(enriched));

    assertDoesNotThrow(
        () -> fixture.service
            .uploadCompleted(fixture.ingestion.ingestionId(), 9L, "object", null)
            .block());
    verifyNoInteractions(fixture.clients);
    verifyNoInteractions(fixture.outbox);
  }

  @Test
  void uploadCompletedSignalsMissingCorrelationForStorageFallback() {
    var fixture = fixture(MediaIngestion.Phase.RECONCILIATION_REQUIRED);
    when(fixture.repo.find(fixture.ingestion.ingestionId())).thenReturn(Mono.empty());

    assertThatThrownBy(() -> fixture.service
        .uploadCompleted(fixture.ingestion.ingestionId(), 9L, "object", null)
        .block())
        .isInstanceOf(IngestionCorrelationNotFoundException.class);
    verifyNoInteractions(fixture.clients, fixture.outbox);
  }

  @Test
  void uploadCompletedPersistsMissingStorageIdentityForReconciliation() {
    var fixture = fixture(MediaIngestion.Phase.RECONCILIATION_REQUIRED);
    when(fixture.repo.find(fixture.ingestion.ingestionId())).thenReturn(Mono.just(fixture.ingestion));
    when(fixture.repo.compareAndSet(eq(fixture.ingestion), any())).thenReturn(Mono.just(true));

    assertDoesNotThrow(
        () -> fixture.service
            .uploadCompleted(fixture.ingestion.ingestionId(), 9L, "object", null)
            .block());
    verify(fixture.repo).compareAndSet(eq(fixture.ingestion), argThat(
        value -> value.storageId().equals(9L) && value.storageKey().equals("object")
            && value.phase() == MediaIngestion.Phase.RECONCILIATION_REQUIRED));
    verifyNoInteractions(fixture.clients, fixture.outbox);
  }

  @Test
  void completedUploadWithSameIdentityIsIdempotent() {
    var fixture = fixture(MediaIngestion.Phase.COMPLETED);
    var completed = fixture.ingestion.recordStorageIdentity(9L, "object");
    when(fixture.repo.find(fixture.ingestion.ingestionId())).thenReturn(Mono.just(completed));

    assertThat(fixture.service.uploadCompleted(
        fixture.ingestion.ingestionId(), 9L, "object", null).block()).isNull();
    verifyNoInteractions(fixture.clients, fixture.outbox);
  }

  @Test
  void completedUploadWithDifferentIdentityIsRejected() {
    var fixture = fixture(MediaIngestion.Phase.COMPLETED);
    var completed = fixture.ingestion.recordStorageIdentity(9L, "object");
    when(fixture.repo.find(fixture.ingestion.ingestionId())).thenReturn(Mono.just(completed));

    assertThatThrownBy(() -> fixture.service.uploadCompleted(
        fixture.ingestion.ingestionId(), 10L, "other", null).block())
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(fixture.clients, fixture.outbox);
  }

  @Test
  void completeDoesNotCallStorageWhenCasLosesRace() {
    var fixture = fixture(MediaIngestion.Phase.AWAITING_UPLOAD);
    when(fixture.repo.compareAndSet(eq(fixture.ingestion), any())).thenReturn(Mono.just(false));

    assertThrows(
        RuntimeException.class,
        () -> fixture.service.complete(fixture.ingestion.ingestionId(), "a", null).block());
    verifyNoInteractions(fixture.clients);
    verifyNoInteractions(fixture.outbox);
  }

  private static Fixture fixture(MediaIngestion.Phase phase) {
    var repo = mock(MediaIngestionRepository.class);
    var clients = mock(DownstreamClients.class);
    var outbox = mock(Outbox.class);
    var compensation = mock(CompensationRepository.class);
    var transactions = mock(TransactionalOperator.class);
    when(transactions.transactional(any(Mono.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var ingestion =
        new MediaIngestion(
            UUID.randomUUID(),
            "a",
            3L,
            "upload",
            phase,
            null,
            2,
            0,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            "k",
            "x",
            1,
            "m",
            null);
    return new Fixture(
        repo,
        clients,
        outbox,
        new MediaIngestionService(repo, clients, outbox, compensation, transactions),
        ingestion);
  }

  private record Fixture(
      MediaIngestionRepository repo,
      DownstreamClients clients,
      Outbox outbox,
      MediaIngestionService service,
      MediaIngestion ingestion) {}
}
