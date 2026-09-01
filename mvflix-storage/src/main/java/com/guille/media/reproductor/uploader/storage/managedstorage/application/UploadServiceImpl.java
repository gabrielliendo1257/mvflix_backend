package com.guille.media.reproductor.uploader.storage.managedstorage.application;

import com.guille.media.reproductor.uploader.storage.managedstorage.application.command.request.CreateUploadCommand;
import com.guille.media.reproductor.uploader.storage.managedstorage.application.command.response.UploadSession;
import com.guille.media.reproductor.uploader.storage.managedstorage.application.command.response.UploadCompletionResult;
import com.guille.media.reproductor.uploader.storage.managedstorage.application.command.response.UploadSummary;
import com.guille.media.reproductor.uploader.storage.shared.security.UserProvider;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.event.UploadCompletedEvent;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.event.UploadFailedEvent;
import com.guille.media.reproductor.uploader.storage.managedstorage.application.UploadFailedIntegrationEvent;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.BucketNotFoundException;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.ExceededQuotaException;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.IllegalStateTransitionException;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.InvalidObjectContentError;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.StorageObjectNotAvailable;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.StorageObjectRemovedException;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.UploadCancelledByUserException;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.UserStorageNotFoundException;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.ExpectedObjectData;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MimeType;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageKeyGenerator;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageObject;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageObject.StorageSessionStatus;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.UploadConfiguration;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.UserStorage;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.ObjectStorageService;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.StorageEventPublisher;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.StorageRepository;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.UserStorageRepository;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.policy.UploadPolicy;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.BucketName;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.PresignedUploadRequest;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageFolder;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageKey;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageLocation;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageMetadata;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class UploadServiceImpl implements UploadService {

  private final ObjectStorageService objectStoragePort;
  private final StorageKeyGenerator storageKeyGenerator;
  private final UploadPolicy uploadPolicy;
  private final StorageRepository storageRepository;
  private final UserProvider userProvider;
  private final UserStorageRepository userStorageRepository;
  private final StorageEventPublisher eventPublisher;
  private final TransactionalOperator transactionalOperator;
  private final TerminalUploadTransition terminalTransition;
  private final UploadCompletionTransaction uploadCompletionTransaction;
  private final StorageOutbox storageOutbox;

  public UploadServiceImpl(
      ObjectStorageService objectStorageService,
      StorageKeyGenerator storageKeyGenerator,
      UploadPolicy uploadPolicy,
      StorageRepository storageRepository,
      UserProvider userProvider,
      UserStorageRepository userStorageRepository,
      StorageEventPublisher eventPublisher,
      TransactionalOperator transactionalOperator,
      TerminalUploadTransition terminalTransition,
      UploadCompletionTransaction uploadCompletionTransaction,
      StorageOutbox storageOutbox) {
    this.objectStoragePort = objectStorageService;
    this.storageKeyGenerator = storageKeyGenerator;
    this.uploadPolicy = uploadPolicy;
    this.storageRepository = storageRepository;
    this.userProvider = userProvider;
    this.userStorageRepository = userStorageRepository;
    this.eventPublisher = eventPublisher;
    this.transactionalOperator = transactionalOperator;
    this.terminalTransition = terminalTransition;
    this.uploadCompletionTransaction = uploadCompletionTransaction;
    this.storageOutbox = storageOutbox;
  }

  @Override
  public Mono<UploadSession> createUploadSession(CreateUploadCommand command) {
    log.info(
        "Creating upload session: filename={}, size={}, mimeType={}",
        command.filename(),
        command.size(),
        command.mimeType());

    return this.userProvider
        .getAuthenticatedUser()
        .switchIfEmpty(
            Mono.error(new AuthenticationCredentialsNotFoundException("No authenticated user")))
        .flatMap(user -> this.userStorageRepository.findByOwnerUsername(user.subject()))
        .switchIfEmpty(
            Mono.error(new UserStorageNotFoundException("No storage registered for the user")))
        .flatMap(
            userStorage ->
                this.objectStoragePort
                    .bucketExists(userStorage.getBucketName())
                    .flatMap(
                        exists -> {
                          if (!exists) {
                            return Mono.error(
                                new BucketNotFoundException(
                                    "Bucket not found: " + userStorage.getBucketName()));
                          }
                           return Mono.justOrEmpty(command.idempotencyKey())
                               .flatMap(key -> Mono.defer(() -> {
                                 Mono<StorageObject> found = this.storageRepository
                                     .findByOwnerAndIdempotencyKey(userStorage.getOwnerUsername(), key);
                                 return found == null ? Mono.empty() : found;
                               }))
                               .flatMap(existing -> this.renewInstructions(existing.getStorageId()))
                               .switchIfEmpty(this.createSession(userStorage, command));
                        }))
        .doOnNext(session -> log.info("Upload session created: uploadId={}", session.uploadId()));
  }

  @Override
  public Mono<UploadSession> findUploadByIdempotencyKey(String idempotencyKey) {
    return this.userProvider.getAuthenticatedUser()
        .switchIfEmpty(Mono.error(new AuthenticationCredentialsNotFoundException(
            "No authenticated user")))
        .flatMap(user -> this.storageRepository
            .findByOwnerAndIdempotencyKey(user.subject(), idempotencyKey))
        .map(object -> new UploadSession(String.valueOf(object.getStorageId()), null,
            object.getStorageKey(), null, null, object.getStorageObjectStatus(),
            new ExpectedObjectData(object.sizeInBytes(), object.contentType())));
  }

  @Override
  public Mono<UploadSession> renewInstructions(Long uploadId) {
    log.info("Renewing upload instructions: uploadId={}", uploadId);
    return this.userProvider
        .getAuthenticatedUser()
        .switchIfEmpty(
            Mono.error(new AuthenticationCredentialsNotFoundException("No authenticated user")))
        .flatMap(user -> this.storageRepository
            .findById(uploadId)
            .switchIfEmpty(Mono.error(
                new StorageObjectNotAvailable("Storage object not available: " + uploadId)))
            .doOnNext(object -> object.ensureOwnedBy(user.subject()))
            .filter(object -> object.getStorageObjectStatus() == StorageSessionStatus.PENDING)
            .switchIfEmpty(Mono.error(new IllegalStateTransitionException(
                "Only PENDING sessions can renew instructions: " + uploadId)))
            .flatMap(object -> this.userStorageRepository
                .findByOwnerUsername(object.getOwnerUsername())
                .switchIfEmpty(Mono.error(new UserStorageNotFoundException(
                    "No storage registered for user: " + object.getOwnerUsername())))
                .flatMap(userStorage -> {
                  StorageLocation location = new StorageLocation(
                      userStorage.getBucketName(), object.getStorageKey());
                  UploadConfiguration configuration = this.uploadPolicy.resolve(
                      object.sizeInBytes(),
                      MimeType.of(object.contentType()));
                  PresignedUploadRequest presignedRequest =
                      new PresignedUploadRequest(configuration.expiration());
                  presignedRequest.setContentType(MimeType.of(object.contentType()));
                  return this.objectStoragePort
                      .createUploadUrl(presignedRequest, location)
                      .map(permissionUrl -> new UploadSession(
                          String.valueOf(object.getStorageId()),
                          permissionUrl.presignedUrl(),
                          object.getStorageKey(),
                          permissionUrl.method(),
                          Instant.now().plus(configuration.expiration()),
                          object.getStorageObjectStatus(),
                          new ExpectedObjectData(
                              object.sizeInBytes(), object.contentType())));
                }))
            .doOnNext(session -> log.info(
                "Upload instructions renewed: uploadId={} expiresAt={}",
                session.uploadId(), session.expiresAt())));
  }

  private Mono<UploadSession> createSession(UserStorage userStorage, CreateUploadCommand command) {
    UploadConfiguration configuration =
        this.uploadPolicy.resolve(command.size(), command.mimeType());

    StorageKey key =
        this.storageKeyGenerator.generate(
            userStorage.getOwnerUsername(), StorageFolder.from(command.mimeType()));
    StorageLocation location = new StorageLocation(userStorage.getBucketName(), key);

    PresignedUploadRequest presignedRequest =
        new PresignedUploadRequest(configuration.expiration());
    presignedRequest.setContentType(command.mimeType());

    StorageMetadata metadata =
        new StorageMetadata(command.mimeType().value(), command.size(), null, Instant.now());
    StorageObject object =
        new StorageObject(
            userStorage.getOwnerUsername(),
            command.idempotencyKey(),
            key,
            metadata,
            Instant.now(),
            null,
            StorageSessionStatus.PENDING);

    return this.objectStoragePort
        .createUploadUrl(presignedRequest, location)
        .flatMap(
            permissionUrl ->
                // Reserva de cuota + persistencia de la sesión comparten una única
                // transacción local: si el save falla, el consumo se revierte y no
                // quedan bytes reservados sin fila que los libere.
                this.transactionalOperator
                    .transactional(
                        this.userStorageRepository
                            .consumeStorage(userStorage.getOwnerUsername(), command.size())
                            .filter(rowsUpdated -> rowsUpdated == 1)
                            .switchIfEmpty(
                                Mono.error(
                                    new ExceededQuotaException(
                                        "Storage quota exceeded for user: "
                                            + userStorage.getOwnerUsername())))
                            .then(this.storageRepository.save(object)))
                    .map(
                        saved ->
                            new UploadSession(
                                String.valueOf(saved.getStorageId()),
                                permissionUrl.presignedUrl(),
                                key,
                                permissionUrl.method(),
                                Instant.now().plus(configuration.expiration()),
                                saved.getStorageObjectStatus(),
                                new ExpectedObjectData(
                                    saved.sizeInBytes(), command.mimeType().value()))));
  }

  @Override
  public Mono<Void> handleObjectRemoved(String objectKey) {
    log.info("Object removed event received from object store: key={}", objectKey);

    return this.storageRepository
        .findByObjectKey(objectKey)
        .flatMap(
            object -> {
              if (object.getStorageObjectStatus() != StorageSessionStatus.PENDING) {
                log.info(
                    "Object removed but session is not PENDING, skipping: key={}, status={}",
                    objectKey,
                    object.getStorageObjectStatus());
                return Mono.<StorageObject>empty();
              }
              object.markFailed();
              // El segundo parámetro de updateStatus es el estado ANTERIOR
              // esperado en la fila (CAS). La fila sigue PENDING: si se
              // esperara FAILED el CAS nunca matchearía. Transición y liberación
              // comparten transacción: o ambas, o ninguna (MinIO ya eliminó el
              // blob; este evento es la reconciliación de ese borrado).
               return this.terminalTransition.transitionAndRelease(
                   object, StorageSessionStatus.PENDING,
                   failed -> this.publishFailed(failed, new StorageObjectRemovedException()));
             })
        .onErrorResume(
            error -> {
              log.warn(
                  "Object removed event could not be reconciled, skipping: key={}, cause={}",
                  objectKey,
                  error.getMessage());
              return Mono.empty();
            })
        .then();
  }

  @Override
  public Flux<UploadSummary> listUploads(int limit) {
    return this.userProvider
        .getAuthenticatedUser()
        .flatMapMany(
            user ->
                this.storageRepository
                    .findRecentByOwner(user.subject(), Math.min(limit, 50))
                    .map(
                        object ->
                            new UploadSummary(
                                object.getStorageId(),
                                object.getStorageKey().key(),
                                object.getStorageObjectStatus(),
                                object.sizeInBytes(),
                                object.getCreatedAt())));
  }

  @Override
  public Mono<Void> cancelUpload(Long uploadId) {
    log.info("Cancelling upload: uploadId={}", uploadId);

    return this.userProvider
        .getAuthenticatedUser()
        .switchIfEmpty(
            Mono.error(new AuthenticationCredentialsNotFoundException("No authenticated user")))
        .flatMap(user -> this.storageRepository
            .findById(uploadId)
            .switchIfEmpty(Mono.error(new StorageObjectNotAvailable(
                "Storage object not available: " + uploadId)))
            .doOnNext(object -> object.ensureOwnedBy(user.subject()))
            .flatMap(object -> {
              if (object.getStorageObjectStatus() != StorageSessionStatus.PENDING) {
                log.info(
                    "Upload is no longer cancellable (not PENDING), skipping: uploadId={}, status={}",
                    uploadId, object.getStorageObjectStatus());
                return Mono.<StorageObject>empty();
              }
              object.markFailed();
              return this.userStorageRepository.findByOwnerUsername(object.getOwnerUsername())
                  .flatMap(userStorage -> this.terminalTransition.transitionAndRelease(
                      object, StorageSessionStatus.PENDING,
                      failed -> this.publishFailed(failed, new UploadCancelledByUserException()))
                      .flatMap(failed -> this.deleteObjectBestEffort(
                          failed, userStorage.getBucketName()).thenReturn(failed)))
                  .onErrorResume(IllegalStateTransitionException.class, race -> {
                    log.warn(
                        "Cancel lost a concurrent transition, skipping: uploadId={}, status={}",
                        uploadId, object.getStorageObjectStatus());
                    return Mono.empty();
                  });
            }))
        .then();
  }

  @Override
  public Mono<UploadCompletionResult> completeUpload(Long uploadId) {
    log.info("Completing upload: uploadId={}", uploadId);

    return this.userProvider
        .getAuthenticatedUser()
        .switchIfEmpty(
            Mono.error(new AuthenticationCredentialsNotFoundException("No authenticated user")))
        .flatMap(
            user ->
                this.storageRepository
                    .findById(uploadId)
                    .switchIfEmpty(
                        Mono.error(new StorageObjectNotAvailable(
                            "Storage object not available: " + uploadId)))
                    .doOnNext(object -> object.ensureOwnedBy(user.subject()))
                    .flatMap(
                        object ->
                            this.userStorageRepository
                                .findByOwnerUsername(object.getOwnerUsername())
                                .switchIfEmpty(
                                    Mono.error(
                                        new UserStorageNotFoundException(
                                            "No storage registered for user: "
                                                + object.getOwnerUsername())))
                                .flatMap(
                                    userStorage ->
                                        this.completeUploadedObject(
                                            object, userStorage.getBucketName(), true)))
        .doOnNext(
            completion -> {
              if (completion.transitioned()) {
                this.publishCompleted(completion.object());
              } else if (completion.pendingVerification()) {
                log.info(
                    "Upload confirmation arrived before the object exists, "
                        + "leaving session pending for webhook reconciliation: uploadId={}",
                    uploadId);
              } else {
                log.info("Upload already completed, skipping: uploadId={}", uploadId);
              }
            })
                    .map(this::toCompletionResult));
  }

  private UploadCompletionResult toCompletionResult(Completion completion) {
    if (completion.pendingVerification()) {
      return UploadCompletionResult.pendingVerification();
    }
    return UploadCompletionResult.completed();
  }

  @Override
  public Mono<UploadSession> getUploadStatus(Long uploadId) {
    log.info("Querying upload status: uploadId={}", uploadId);

    return this.storageRepository
        .findById(uploadId)
        .switchIfEmpty(
            Mono.error(new StorageObjectNotAvailable("Storage object not available: " + uploadId)))
        .flatMap(
            object ->
                this.userProvider
                    .getAuthenticatedUser()
                    .map(user -> {
                      object.ensureOwnedBy(user.subject());
                      return object;
                    }))
        .map(
            object ->
                new UploadSession(
                    String.valueOf(object.getStorageId()),
                    null,
                    object.getStorageKey(),
                    null,
                    null,
                    object.getStorageObjectStatus(),
                    new ExpectedObjectData(
                        object.sizeInBytes(), object.contentType())));
  }

  @Override
  public Mono<Void> completeUploadByKey(String objectKey) {
    log.info("Reconciling upload from object store event: key={}", objectKey);

    return this.storageRepository
        .findByObjectKey(objectKey)
        .flatMap(
            object -> {
              StorageSessionStatus status = object.getStorageObjectStatus();
              if (status == StorageSessionStatus.EXPIRED
                  || status == StorageSessionStatus.FAILED) {
                log.info(
                    "Object store event for non-active session, removing orphan object: "
                        + "key={}, status={}",
                    objectKey,
                    status);
                return this.cleanupOrphanObject(object).then(Mono.<Completion>empty());
              }
              return this.userStorageRepository
                  .findByOwnerUsername(object.getOwnerUsername())
                  .switchIfEmpty(
                      Mono.error(
                          new UserStorageNotFoundException(
                              "No storage registered for user: "
                                  + object.getOwnerUsername())))
                  .flatMap(
                      userStorage ->
                          this.completeUploadedObject(
                              object, userStorage.getBucketName(), false));
            })
        .doOnNext(
            completion -> {
              if (completion.transitioned()) {
                this.publishCompleted(completion.object());
              } else {
                log.info("Object already completed, skipping event reconciliation: key={}", objectKey);
              }
            })
        .onErrorResume(
            error -> {
              log.warn(
                  "Object store event could not be reconciled, skipping: key={}, cause={}",
                  objectKey,
                  error.getMessage());
              return Mono.empty();
            })
        .then();
  }

  private void publishCompleted(StorageObject completed) {
    this.eventPublisher.publish(
        new UploadCompletedEvent(
            completed.getStorageId(),
            completed.getOwnerUsername(),
            completed.getStorageKey().key(),
            completed.contentType(),
            completed.sizeInBytes(),
            Instant.now()));
  }

  /**
   * Evento tardío de MinIO para una sesión que ya no está activa (EXPIRED/FAILED): el objeto
   * que llegó después de la expiración es basura y se borra del bucket (la fila se conserva
   * como historial; su cuota ya fue liberada).
   */
  private Mono<Void> cleanupOrphanObject(StorageObject object) {
    return this.userStorageRepository
        .findByOwnerUsername(object.getOwnerUsername())
        .flatMap(
            userStorage ->
                this.deleteObjectBestEffort(object, userStorage.getBucketName()))
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.warn(
                      "No user storage for orphan cleanup, skipping: uploadId={}",
                      object.getStorageId());
                  return Mono.empty();
                }));
  }

  private Mono<Completion> completeUploadedObject(
      StorageObject object, BucketName bucket, boolean clientConfirmation) {
    StorageLocation location = new StorageLocation(bucket, object.getStorageKey());

    return this.objectStoragePort
        .objectExists(location)
        .flatMap(
            exists -> {
              if (!exists) {
                if (clientConfirmation) {
                  return Mono.just(new Completion(object, false, true));
                }
                return this.releaseAndFail(
                    object,
                    bucket,
                    new StorageObjectNotAvailable(
                        "Storage object not available: " + object.getStorageId()));
              }
              return this.verifyMetadataAndComplete(object, bucket, location);
            });
  }

  private Mono<Completion> verifyMetadataAndComplete(
      StorageObject object, BucketName bucket, StorageLocation location) {
    return this.objectStoragePort
        .getMetadata(location)
        .flatMap(
            metadata ->
                Mono.fromCallable(
                        () -> {
                          object.ensureValidContentLength(metadata.contentLength());
                          return object.complete();
                        })
                    .onErrorResume(
                        InvalidObjectContentError.class,
                        contentError ->
                            this.releaseAndFail(object, bucket, contentError)))
        .flatMap(
            transitioned ->
                transitioned
                    ? this.uploadCompletionTransaction
                        .complete(object)
                        .map(saved -> new Completion(saved, true, false))
                        .onErrorResume(
                            IllegalStateTransitionException.class,
                            race -> this.raceLostToAnotherCompletion(object))
                    : Mono.just(new Completion(object, false, false)));
  }

  /**
   * La transición perdió la carrera contra el otro camino de completado (webhook de MinIO o
   * confirmación del cliente). Si el objeto ya quedó COMPLETED, es el mismo resultado esperado
   * (idempotente); si quedó en otro estado (p.ej. FAILED por cancelación), se propaga el error.
   */
  private Mono<Completion> raceLostToAnotherCompletion(StorageObject object) {
    return this.storageRepository
        .findById(object.getStorageId())
        .flatMap(
            current -> {
              if (current.getStorageObjectStatus() == StorageSessionStatus.COMPLETED) {
                log.info(
                    "Upload already completed by another path (webhook/cliente), "
                        + "treating as success: uploadId={}",
                    current.getStorageId());
                return Mono.just(new Completion(current, false, false));
              }
              return Mono.error(
                  new IllegalStateTransitionException(
                      "Cannot transition object " + current.getStorageId()
                          + ": expected status PENDING in database, concurrent modification"
                          + " detected"));
            });
  }

  private <T> Mono<T> releaseAndFail(
      StorageObject object, BucketName bucket, RuntimeException error) {
    return Mono.defer(
        () -> {
          if (!object.markFailed()) {
            return Mono.error(error);
          }
          // Transición + liberación atómicas; el borrado del blob ocurre
          // después y best effort: un blob huérfano es reconciliable, una
          // cuota descontada dos veces no.
          return this.terminalTransition
              .transitionAndRelease(object, StorageSessionStatus.PENDING,
                  failed -> this.publishFailed(failed, error))
              .flatMap(failed -> this.deleteObjectBestEffort(failed, bucket).thenReturn(failed))
              .onErrorResume(
                  IllegalStateTransitionException.class,
                  race -> {
                    log.warn(
                        "Fail lost a concurrent transition, skipping persist and release: uploadId={}",
                        object.getStorageId());
                    return Mono.error(error);
                  })
              .then(Mono.error(error));
        });
  }

  private Mono<Void> publishFailed(StorageObject failed, RuntimeException error) {
    UUID eventId = UUID.randomUUID();
    UploadFailedEvent domainEvent = new UploadFailedEvent(
        failed.getStorageId(), failed.getOwnerUsername(), failed.getStorageKey().key(),
        error.getMessage(), Instant.now());
    return this.storageOutbox.append(new UploadFailedIntegrationEvent(
        eventId, 1, Instant.now(), "system", failed.getOwnerUsername(), eventId,
        String.valueOf(failed.getStorageId()), new UploadFailedIntegrationEvent.UploadFailedPayload(
            failed.getStorageId(), failed.getOwnerUsername(), failed.getStorageKey().key(),
            error.getMessage())))
        .doOnSuccess(ignored -> this.eventPublisher.publish(domainEvent));
  }

  /**
   * Borra el objeto del bucket sin romper el flujo (el DELETE de S3 es idempotente). Se usa
   * para limpiar objetos huérfanos de sesiones EXPIRED/FAILED/canceladas.
   */
  private Mono<Void> deleteObjectBestEffort(StorageObject object, BucketName bucket) {
    return Mono.<Void>fromRunnable(
            () ->
                this.objectStoragePort.delete(
                    new StorageLocation(bucket, object.getStorageKey())))
        .onErrorResume(
            error -> {
              log.warn(
                  "Could not delete object from bucket (best effort), uploadId={}, cause={}",
                  object.getStorageId(),
                  error.getMessage());
              return Mono.empty();
            });
  }

  private record Completion(
      StorageObject object, boolean transitioned, boolean pendingVerification) {}
}
