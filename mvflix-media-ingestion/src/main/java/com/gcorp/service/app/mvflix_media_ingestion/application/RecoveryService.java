package com.gcorp.service.app.mvflix_media_ingestion.application;

import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion;
import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion.Phase;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Service
public class RecoveryService {
  private final MediaIngestionRepository repository;
  private final DownstreamClients clients;
  private final Outbox outbox;
  private final CompensationRepository compensations;
  private final TransactionalOperator transactions;

  public RecoveryService(
      MediaIngestionRepository repository,
      DownstreamClients clients,
      Outbox outbox,
      CompensationRepository compensations,
      TransactionalOperator transactions) {
    this.repository = repository;
    this.clients = clients;
    this.outbox = outbox;
    this.compensations = compensations;
    this.transactions = transactions;
  }

  public Mono<MediaIngestion> recover(MediaIngestion ingestion) {
    return switch (ingestion.phase()) {
      case FINALIZING_CATALOG, RECONCILIATION_REQUIRED -> reconcile(ingestion);
      case STARTING, PREPARING_CATALOG, PREPARING_UPLOAD -> earlyPhase(ingestion);
      default -> Mono.just(ingestion);
    };
  }

  public Mono<MediaIngestion> rescheduleAfterError(MediaIngestion ingestion, Throwable error) {
    return reschedule(ingestion, "recovery worker error: " + error);
  }

  private Mono<MediaIngestion> reconcile(MediaIngestion i) {
    if (i.catalogItemId() == null || i.uploadId() == null) {
      return reschedule(i, "recovery requires catalogItemId and uploadId");
    }
    return clients
        .catalogStatus(i.catalogItemId(), i.actorId())
        .zipWith(clients.storageStatus(i.uploadId(), i.actorId()))
        .flatMap(
            states -> {
              var catalog = states.getT1();
              var storage = states.getT2();
              boolean catalogReady = "READY".equalsIgnoreCase(catalog.status());
              boolean storageComplete = "COMPLETED".equalsIgnoreCase(storage.status());
              if (catalogReady && storageComplete) {
                return complete(i);
              }
              if (storageComplete && "DRAFT".equalsIgnoreCase(catalog.status())) {
                Long objectId =
                    i.storageId() != null
                        ? i.storageId()
                        : storage.objectId() != null ? storage.objectId() : numericId(i.uploadId());
                String objectKey = i.storageKey() != null ? i.storageKey() : storage.objectKey();
                if (objectId != null && objectKey != null) {
                  return clients
                      .completeCatalog(i.catalogItemId(), objectKey, objectId, i.actorId())
                      .then(complete(i));
                }
                return reschedule(i, "storage completed but object identity is unavailable");
              }
              return reschedule(
                  i,
                  "authoritative states are not complete: catalog="
                      + catalog.status()
                      + ", storage="
                      + storage.status());
            })
        .onErrorResume(error -> reschedule(i, "authoritative status unavailable: " + error));
  }

  private Mono<MediaIngestion> earlyPhase(MediaIngestion i) {
    Mono<DownstreamClients.StorageStatus> storage =
        i.uploadId() == null ? Mono.empty() : clients.storageStatus(i.uploadId(), i.actorId());
    return storage
        .defaultIfEmpty(new DownstreamClients.StorageStatus("UNKNOWN", null, null))
        .flatMap(
            state -> {
               if ("PENDING".equalsIgnoreCase(state.status()) && i.uploadId() != null) {
                 return compensations
                     .schedule(i.ingestionId(), "CANCEL_UPLOAD")
                     .then(i.catalogItemId() == null
                         ? Mono.empty()
                         : compensations.schedule(i.ingestionId(), "DISCARD_DRAFT"))
                     .then(
                        mark(
                            i, Phase.FAILED, "cannot resume " + i.phase() + "; upload is pending"));
              }
               if ("COMPLETED".equalsIgnoreCase(state.status())) {
                 return finishCompletedUpload(i, state);
               }
              return mark(
                  i,
                  Phase.FAILED,
                  "cannot resume " + i.phase() + "; original draft is not persisted");
            })
         .onErrorResume(
             error -> reschedule(i, "authoritative storage status unavailable: " + error));
   }

   private Mono<MediaIngestion> finishCompletedUpload(
       MediaIngestion i, DownstreamClients.StorageStatus storage) {
      if (i.catalogItemId() == null) {
        return mark(i, Phase.RECONCILIATION_REQUIRED,
            "upload is completed but catalog identity is unavailable");
     }
     Long objectId = i.storageId() != null ? i.storageId() : storage.objectId();
     String objectKey = i.storageKey() != null ? i.storageKey() : storage.objectKey();
      if (objectId == null || objectKey == null) {
        return mark(i, Phase.RECONCILIATION_REQUIRED,
            "upload is completed but object identity is unavailable");
     }
     var finalizing = new MediaIngestion(
         i.ingestionId(), i.actorId(), i.catalogItemId(), i.uploadId(),
         Phase.FINALIZING_CATALOG, null, i.version() + 1, i.retryCount(),
         i.createdAt(), Instant.now(), i.nextAttemptAt(), i.idempotencyKey(),
         i.fileName(), i.fileSize(), i.mimeType(), i.uploadUrl(), objectId,
         objectKey, i.requestFingerprint(), i.causationId());
      return transactions
          .transactional(repository.compareAndSet(i, finalizing))
          .flatMap(ok -> {
            if (!ok) return reload(i);
            return clients.completeCatalog(i.catalogItemId(), objectKey, objectId, i.actorId())
                .then(complete(finalizing))
                .onErrorResume(error -> mark(
                    finalizing, Phase.RECONCILIATION_REQUIRED,
                    "completed upload finalization requires reconciliation: " + error));
          });
   }

  private Mono<MediaIngestion> complete(MediaIngestion i) {
    var next = i.transition(Phase.COMPLETED, null, null, null);
    return transactions.transactional(
        repository
            .compareAndSet(i, next)
            .flatMap(
                ok ->
                    ok
                        ? outbox.completed(next).thenReturn(next)
                        : reload(i)));
    }

   private Mono<MediaIngestion> reload(MediaIngestion expected) {
     return repository.find(expected.ingestionId())
         .switchIfEmpty(Mono.error(new IllegalStateException(
             "recovery CAS lost but persisted ingestion was not found")));
   }

  private Mono<MediaIngestion> reschedule(MediaIngestion i, String reason) {
    return mark(
        i,
        i.phase() == Phase.FINALIZING_CATALOG ? Phase.RECONCILIATION_REQUIRED : i.phase(),
        reason);
  }

  private Long numericId(String value) {
    try {
      return value == null ? null : Long.valueOf(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private Mono<MediaIngestion> mark(MediaIngestion i, Phase phase, String reason) {
    long delay = Math.min(3600, 30L * (1L << Math.min(i.retryCount(), 6)));
     var next = i.rescheduled(phase, "RECOVERY_REQUIRED", reason, delay);
    return transactions.transactional(
        repository
            .compareAndSet(i, next)
            .flatMap(
                ok -> {
                   if (!ok) return Mono.error(new IllegalStateException("recovery CAS failed"));
                   Mono<MediaIngestion> saved =
                       repository.find(i.ingestionId()).switchIfEmpty(Mono.just(next));
                   return phase == Phase.FAILED
                       ? scheduleDraftCompensation(i)
                           .then(saved.flatMap(value -> outbox.failed(value).thenReturn(value)))
                       : saved;
                 }));
   }

   private Mono<Void> scheduleDraftCompensation(MediaIngestion i) {
     return i.catalogItemId() == null
         ? Mono.empty()
         : compensations.schedule(i.ingestionId(), "DISCARD_DRAFT");
   }
}
