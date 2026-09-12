package com.gcorp.service.app.mvflix_media_ingestion.application;

import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion;
import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion.Phase;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Service
public class MediaIngestionService {
  private final MediaIngestionRepository repository;
  private final DownstreamClients clients;
  private final Outbox outbox;
  private final CompensationRepository compensations;
  private final TransactionalOperator transactions;

  public MediaIngestionService(
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

  public Mono<MediaIngestion> create(
      String actor,
      String key,
      Map<String, Object> draft,
      String fileName,
      long size,
      String mime) {
    return create(actor, actor, key, draft, fileName, size, mime);
  }

  public Mono<MediaIngestion> create(
      String actor,
      String audience,
      String key,
      Map<String, Object> draft,
      String fileName,
      long size,
      String mime) {
    String fingerprint = fingerprint(draft, fileName, size, mime);
    return repository
        .findByKey(actor, key)
        .flatMap(existing -> sameRequest(existing, fingerprint)
            ? Mono.just(existing)
            : Mono.error(new IllegalStateException("idempotency key reused with different request")))
        .switchIfEmpty(Mono.defer(() -> clients.mediaIngestionEligibility(actor)
            .switchIfEmpty(Mono.error(new IllegalStateException("user eligibility unavailable")))
            .flatMap(eligibility -> eligibility.allowed()
             ? createInternal(actor, audience, key, draft, fileName, size, mime, fingerprint)
                : Mono.error(new IllegalStateException("media ingestion not allowed for user")))));
  }

  private Mono<MediaIngestion> createInternal(
      String actor,
      String audience,
      String key,
      Map<String, Object> draft,
      String fileName,
      long size,
      String mime,
      String fingerprint) {
    return repository
        .findByKey(actor, key)
        .flatMap(existing -> sameRequest(existing, fingerprint)
            ? Mono.just(existing)
            : Mono.error(new IllegalStateException("idempotency key reused with different request")))
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  var now = Instant.now();
                  var i =
                      new MediaIngestion(
                          UUID.randomUUID(),
                           actor,
                          null,
                          null,
                          Phase.STARTING,
                          null,
                          0,
                          0,
                          now,
                          now,
                          now,
                          key,
                          fileName,
                          size,
                           mime,
                           null,
                           null,
                           null,
                            fingerprint,
                            null,
                            audience);
                   return repository
                       .insert(i)
                      .flatMap(
                          saved ->
                              step(saved, Phase.PREPARING_CATALOG, null, null)
                                  .then(
                                       clients.createCatalogDraft(
                                           draft,
                                           actor,
                                           saved.ingestionId() + ":create-catalog-draft",
                                           saved.ingestionId().toString()))
                                  .flatMap(
                                      catalog ->
                                          repository
                                              .find(saved.ingestionId())
                                              .flatMap(
                                                  current ->
                                                      step(
                                                          current,
                                                          Phase.PREPARING_UPLOAD,
                                                          catalog,
                                                          null)))
                                  .flatMap(x -> prepare(x, fileName, size, mime))
                                   .onErrorResume(e -> fail(saved, e))
                       .onErrorResume(DataIntegrityViolationException.class,
                           e -> repository.findByKey(actor, key)
                               .flatMap(existing -> sameRequest(existing, fingerprint)
                                   ? Mono.just(existing)
                                   : Mono.error(new IllegalStateException(
                                       "idempotency key reused with different request")))));
                 }));
  }

  private static boolean sameRequest(MediaIngestion existing, String fingerprint) {
    return fingerprint.equals(existing.requestFingerprint());
  }

  static String fingerprint(Map<String, Object> draft, String fileName, long size, String mime) {
    try {
      var input = new TreeMap<String, Object>();
      input.put("draft", new TreeMap<>(draft));
      input.put("fileName", fileName);
      input.put("size", size);
      input.put("mime", mime);
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(("media-ingestion:v1|" + canonical(input)).getBytes(StandardCharsets.UTF_8));
      var result = new StringBuilder(64);
      for (byte value : digest) result.append(String.format("%02x", value));
      return result.toString();
    } catch (Exception error) {
      throw new IllegalStateException("could not fingerprint ingestion request", error);
    }
  }

  private static String canonical(Object value) {
    if (value == null) return "null";
    if (value instanceof Map<?, ?> map) {
      var result = new StringBuilder("{");
      map.entrySet().stream()
          .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
          .forEach(entry -> {
            String key = String.valueOf(entry.getKey());
            result.append(key.length()).append(':').append(key).append('=')
                .append(canonical(entry.getValue())).append(';');
          });
      return result.append('}').toString();
    }
    if (value instanceof List<?> list) {
      return list.stream().map(MediaIngestionService::canonical)
          .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
    String text = String.valueOf(value);
    return text.length() + ":" + text;
  }

  private Mono<MediaIngestion> prepare(MediaIngestion i, String name, long size, String mime) {
    return clients
        .prepareUpload(name, size, mime, i.actorId(), i.ingestionId() + ":prepare-upload")
        .flatMap(
            u -> {
               var n = i.awaitUpload(u.uploadId(), u.uploadUrl(), u.storageKey(), u.strategy(),
                   u.partSizeBytes(), u.totalParts());
              return inTransaction(
                  repository
                      .compareAndSet(i, n)
                      .flatMap(
                          ok ->
                              ok
                                  ? outbox.started(n).thenReturn(n)
                                  : Mono.error(new IllegalStateException("CAS failed"))));
            })
        .onErrorResume(e -> fail(i, e));
  }

  public Mono<MediaIngestion> get(UUID id, String actor) {
    return repository.find(id).filter(i -> i.actorId().equals(actor));
  }

  public Mono<MediaIngestion> cancel(UUID id, String actor) {
    return get(id, actor)
        .flatMap(
            i -> {
              if (i.phase() == Phase.CANCELLED || i.phase() == Phase.COMPLETED) return Mono.just(i);
              var n = i.transition(Phase.CANCELLING, null, null, null);
              return repository
                  .compareAndSet(i, n)
                  .flatMap(
                      ok ->
                               ok
                               ? scheduleCompensations(i)
                                   .then(i.uploadId() == null
                                       ? Mono.empty()
                                        : cancelUpload(i, actor, id + ":cancel-upload"))
                                  .then(
                                      inTransaction(
                                          repository
                                              .compareAndSet(
                                                  n,
                                                  n.transition(Phase.CANCELLED, null, null, null))
                                              .flatMap(
                                                  cancelled ->
                                                      cancelled
                                                          ? repository
                                                              .find(id)
                                                              .flatMap(
                                                                  x ->
                                                                      outbox
                                                                          .cancelled(x)
                                                                          .thenReturn(x))
                                                          : Mono.error(
                                                              new IllegalStateException(
                                                                  "CAS failed")))))
                              : Mono.error(new IllegalStateException("CAS failed")));
            })
        .onErrorResume(e -> get(id, actor).flatMap(i -> fail(i, e)));
  }

  public Mono<MediaIngestion> complete(UUID id, String actor, Long reportedSize) {
    return complete(id, actor, null, null, reportedSize);
  }

  public Mono<MediaIngestion> complete(
      UUID id, String actor, Long objectId, String objectKey, Long reportedSize) {
    return complete(id, actor, objectId, objectKey, reportedSize, java.util.List.of());
  }

  public Mono<MediaIngestion> complete(
      UUID id, String actor, Long objectId, String objectKey, Long reportedSize,
      java.util.List<DownstreamClients.CompletedPart> parts) {
    return get(id, actor)
        .flatMap(
            i -> {
              if (i.phase() == Phase.COMPLETED) return Mono.just(i);
              if (i.phase() != Phase.AWAITING_UPLOAD) {
                return Mono.error(
                    new IllegalStateException("cannot complete ingestion in phase " + i.phase()));
              }
               if (i.uploadId() == null)
                 return Mono.error(new IllegalStateException("upload session unavailable"));
               if (reportedSize != null && !reportedSize.equals(i.fileSize())) {
                 return rejectInconsistentSize(i, actor, reportedSize);
               }
               if (objectId != null && i.storageId() != null && !objectId.equals(i.storageId()))
                return Mono.error(new IllegalArgumentException("object_id does not match upload"));
              if (objectKey != null && i.storageKey() != null && !objectKey.equals(i.storageKey()))
                return Mono.error(new IllegalArgumentException("object_key does not match upload"));

              // CAS gives one completion request ownership without claiming the saga's final state.
               var claimed = i.transition(Phase.VERIFYING_UPLOAD, null, null, null);
              return repository
                  .compareAndSet(i, claimed)
                  .flatMap(
                      ok ->
                          ok
                               ? completeUpload(i, actor, id + ":complete-upload", parts)
                                   .then(repository.find(id))
                              : Mono.error(new IllegalStateException("CAS failed")));
            });
  }

  public Mono<Void> uploadCompleted(UUID id, long objectId, String objectKey, String causation) {
    return repository
        .find(id)
        .switchIfEmpty(Mono.error(new IngestionCorrelationNotFoundException(id)))
        .flatMap(i -> finalize(i, objectId, objectKey, parseUuid(causation)).then())
        .then();
  }

  public Mono<Void> uploadCompletedByUploadId(
      String uploadId, long objectId, String objectKey, String causation) {
    return repository
        .findByUploadId(uploadId)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("unknown uploadId")))
        .flatMap(i -> finalize(i, objectId, objectKey, parseUuid(causation)).then())
        .then();
  }

  public Mono<Void> uploadCompletedByStorageId(
      long storageId, long objectId, String objectKey, String causation) {
    return repository
        .findByStorageId(storageId)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("unknown storageId")))
        .flatMap(i -> finalize(i, objectId, objectKey, parseUuid(causation)).then())
        .then();
  }

  private Mono<MediaIngestion> step(MediaIngestion i, Phase phase, Long catalog, String upload) {
    return repository
        .compareAndSet(i, i.transition(phase, catalog, upload, null))
        .flatMap(
            ok ->
                ok
                    ? repository.find(i.ingestionId())
                    : Mono.error(new IllegalStateException("CAS failed")));
  }

  private Mono<Void> completeUpload(MediaIngestion i, String actor, String key,
      java.util.List<DownstreamClients.CompletedPart> parts) {
    if (parts.isEmpty()) {
      return "PRESIGNED_MULTIPART".equals(i.uploadStrategy())
          ? Mono.empty()
          : clients.requestUploadCompletion(i.uploadId(), actor, key);
    }
    return clients.requestUploadCompletion(i.uploadId(), actor, key, i.uploadStrategy(), parts);
  }

  private Mono<Void> cancelUpload(MediaIngestion i, String actor, String key) {
    return "PRESIGNED_MULTIPART".equals(i.uploadStrategy())
        ? clients.cancelUpload(i.uploadId(), actor, key, i.uploadStrategy())
        : clients.cancelUpload(i.uploadId(), actor, key);
  }

  private Mono<MediaIngestion> fail(MediaIngestion i, Throwable e) {
    return fail(i, "DOWNSTREAM_UNAVAILABLE",
        e.getClass().getSimpleName() + ":" + String.valueOf(e.getMessage()));
  }

  private Mono<MediaIngestion> fail(MediaIngestion i, String code, String detail) {
    return repository
        .find(i.ingestionId())
        .flatMap(
            current -> {
              if (current.phase() == Phase.COMPLETED || current.phase() == Phase.CANCELLED)
                return Mono.just(current);
               var x =
                    current.failed(code, detail);
               Mono<Void> cleanup = scheduleCompensations(current);
              return inTransaction(
                  repository
                      .compareAndSet(current, x)
                      .flatMap(
                          ok ->
                              ok
                                  ? cleanup
                                      .then(repository.find(current.ingestionId()))
                                      .flatMap(saved -> outbox.failed(saved).thenReturn(saved))
                                  : Mono.error(new IllegalStateException("CAS failed"))));
             });
  }

  private Mono<MediaIngestion> rejectInconsistentSize(
      MediaIngestion i, String actor, long reportedSize) {
    String reason = "El tamaño reportado no coincide con el tamaño esperado: expected="
        + i.fileSize() + ", reported=" + reportedSize;
    return Mono.justOrEmpty(clients.reportViolation(actor, "UPLOAD_INCONSISTENT: " + reason))
            .onErrorResume(error -> Mono.empty())
        .then(fail(i, "UPLOAD_INCONSISTENT", reason))
        .then(Mono.error(new UploadInconsistentException(reason)));
  }

   private Mono<Void> scheduleCompensations(MediaIngestion i) {
     if (compensations == null) return Mono.empty();
     Mono<Void> discardDraft = i.catalogItemId() == null
         ? Mono.empty()
         : compensations.schedule(i.ingestionId(), "DISCARD_DRAFT");
     Mono<Void> cancelUpload = i.uploadId() == null
         ? Mono.empty()
         : compensations.schedule(i.ingestionId(), "CANCEL_UPLOAD");
     return Mono.when(discardDraft, cancelUpload)
         .then();
   }

  private UUID parseUuid(String value) {
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private Mono<MediaIngestion> finalize(
      MediaIngestion i, long objectId, String objectKey, UUID causation) {
    if (i.phase() == Phase.COMPLETED) {
      if (Long.valueOf(objectId).equals(i.storageId()) && Objects.equals(objectKey, i.storageKey()))
        return Mono.just(i);
      return Mono.error(new IllegalStateException("storage identity does not match ingestion"));
    }
    if (causation != null) i = i.withCausationId(causation);
    if (i.phase() == Phase.RECONCILIATION_REQUIRED) {
      var recorded = i.recordStorageIdentity(objectId, objectKey);
      if (recorded == i) return Mono.just(i);
      return repository.compareAndSet(i, recorded)
          .flatMap(ok -> ok ? Mono.just(recorded) : Mono.error(new IllegalStateException("CAS failed")));
    }
    if (i.phase() != Phase.AWAITING_UPLOAD && i.phase() != Phase.VERIFYING_UPLOAD
        && i.phase() != Phase.FINALIZING_CATALOG)
      return Mono.error(new IllegalStateException("ingestion not awaiting upload"));
    var n =
        i.phase() == Phase.FINALIZING_CATALOG
            ? new MediaIngestion(
                i.ingestionId(),
                i.actorId(),
                i.catalogItemId(),
                i.uploadId(),
                Phase.FINALIZING_CATALOG,
                i.failureCode(),
                i.version() + 1,
                i.retryCount(),
                i.createdAt(),
                Instant.now(),
                i.nextAttemptAt(),
                i.idempotencyKey(),
                i.fileName(),
                i.fileSize(),
                i.mimeType(),
                 i.uploadUrl(),
                 objectId,
                 objectKey,
                 i.requestFingerprint(),
                 i.causationId())
            : new MediaIngestion(
                i.ingestionId(),
                i.actorId(),
                i.catalogItemId(),
                i.uploadId(),
                Phase.FINALIZING_CATALOG,
                null,
                i.version() + 1,
                i.retryCount(),
                i.createdAt(),
                Instant.now(),
                i.nextAttemptAt(),
                i.idempotencyKey(),
                i.fileName(),
                i.fileSize(),
                i.mimeType(),
                 i.uploadUrl(),
                 objectId,
                 objectKey,
                 i.requestFingerprint(),
                 i.causationId(),
                 i.audienceId());
     return repository.compareAndSet(i, n)
        .flatMap(
            ok ->
                ok
                    ? clients
                        .completeCatalog(n.catalogItemId(), objectKey, objectId, n.actorId())
                        .then(
                            inTransaction(
                                repository
                                    .compareAndSet(
                                        n, n.transition(Phase.COMPLETED, null, null, null))
                                    .flatMap(
                                        completed ->
                                            completed
                                                ? repository
                                                    .find(n.ingestionId())
                                                    .flatMap(x -> outbox.completed(x).thenReturn(x))
                                                : Mono.error(
                                                    new IllegalStateException("CAS failed")))))
                    : Mono.error(new IllegalStateException("CAS failed")))
        .onErrorResume(e -> reconcile(n, e));
  }

  private Mono<MediaIngestion> reconcile(MediaIngestion i, Throwable e) {
    var n =
        new MediaIngestion(
            i.ingestionId(),
            i.actorId(),
            i.catalogItemId(),
            i.uploadId(),
                 Phase.RECONCILIATION_REQUIRED,
                 "CATALOG_UNAVAILABLE",
                 e.toString(),
            i.version() + 1,
            i.retryCount() + 1,
            i.createdAt(),
            Instant.now(),
            Instant.now().plusSeconds(60),
            i.idempotencyKey(),
            i.fileName(),
            i.fileSize(),
            i.mimeType(),
            i.uploadUrl(),
                i.storageId(),
                 i.storageKey(),
                 i.requestFingerprint(),
                  i.causationId(),
                  i.audienceId());
    return inTransaction(
        repository
            .compareAndSet(i, n)
            .flatMap(
                ok ->
                    ok
                        ? outbox.failed(n).thenReturn(n)
                        : Mono.error(new IllegalStateException("CAS failed"))));
  }

  private <T> Mono<T> inTransaction(Mono<T> work) {
    return transactions.transactional(work);
  }
}
