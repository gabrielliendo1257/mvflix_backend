package com.guille.media.reproductor.uploader.storage.managedstorage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.springframework.transaction.reactive.TransactionalOperator;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guille.media.reproductor.uploader.storage.managedstorage.application.command.request.CreateUploadCommand;
import com.guille.media.reproductor.uploader.storage.managedstorage.application.command.response.UploadCompletionResult;
import com.guille.media.reproductor.uploader.storage.managedstorage.application.command.response.UploadSummary;
import com.guille.media.reproductor.uploader.storage.shared.security.AuthenticatedUser;
import com.guille.media.reproductor.uploader.storage.shared.security.UserProvider;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.event.UploadCompletedEvent;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.event.UploadFailedEvent;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.BucketNotFoundException;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.ExceededQuotaException;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.InvalidObjectContentError;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.IllegalStateTransitionException;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.StorageObjectNotAvailable;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageKeyGenerator;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageQuota;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageUsage;
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
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MimeType;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.PermissionUrl;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.PresignedUploadRequest;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageKey;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageLocation;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageMetadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

class UploadServiceImplTest {

  private final ObjectStorageService objectStoragePort = mock(ObjectStorageService.class);
  private final StorageKeyGenerator storageKeyGenerator = new StorageKeyGenerator();
  private final UploadPolicy uploadPolicy = mock(UploadPolicy.class);
  private final StorageRepository storageRepository = mock(StorageRepository.class);
  private final UserProvider userProvider = mock(UserProvider.class);
  private final UserStorageRepository userStorageRepository = mock(UserStorageRepository.class);
  private final StorageEventPublisher eventPublisher = mock(StorageEventPublisher.class);
  private final StorageOutbox storageOutbox = mock(StorageOutbox.class);
  private final TransactionalOperator transactionalOperator =
      mock(TransactionalOperator.class);
  private final TerminalUploadTransition terminalTransition =
      new TerminalUploadTransition(storageRepository, userStorageRepository, transactionalOperator);

  private final UploadServiceImpl service =
      new UploadServiceImpl(
          objectStoragePort,
          storageKeyGenerator,
          uploadPolicy,
          storageRepository,
          userProvider,
          userStorageRepository,
          eventPublisher,
          transactionalOperator,
           terminalTransition,
           new UploadCompletionTransaction(storageRepository, storageOutbox, transactionalOperator),
           storageOutbox);

  @BeforeEach
  void passThroughTransaction() {
    when(this.transactionalOperator.transactional(any(Mono.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(this.storageOutbox.append(any(StorageIntegrationEvent.class)))
        .thenReturn(Mono.empty());
  }

  private static final AuthenticatedUser PEPE = new AuthenticatedUser("pepe", "pepe@mvflix.dev");

  private static final UserStorage PEPE_STORAGE =
      new UserStorage(
          1L, BucketName.of("movies"), "pepe", StorageQuota.ofGigabytes(10), new StorageUsage(0));

  @BeforeEach
  void setUp() {
    when(this.uploadPolicy.resolve(anyLong(), any(MimeType.class)))
        .thenReturn(new UploadConfiguration(Duration.ofMinutes(15)));
  }

  private StorageObject pendingObject(long storageId) {
    return new StorageObject(
        "pepe",
        new StorageKeyGenerator().generate("pepe", com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageFolder.from(MimeType.of("video/mp4"))),
        new StorageMetadata("video/mp4", 1024, null, Instant.now()),
        Instant.now(),
        storageId,
        StorageSessionStatus.PENDING);
  }

  private StorageObject completedObject(long storageId) {
    return new StorageObject(
        "pepe",
        new StorageKeyGenerator().generate("pepe", com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageFolder.from(MimeType.of("video/mp4"))),
        new StorageMetadata("video/mp4", 1024, null, Instant.now()),
        Instant.now(),
        storageId,
        StorageSessionStatus.COMPLETED);
  }

  @Test
  void createUploadSessionReservesQuotaAndReturnsPresignedUrl() {
    StorageObject saved = this.pendingObject(1L);

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.userStorageRepository.findByOwnerUsername("pepe")).thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.bucketExists(BucketName.of("movies"))).thenReturn(Mono.just(true));
    when(this.objectStoragePort.createUploadUrl(any(PresignedUploadRequest.class), any(StorageLocation.class)))
        .thenReturn(Mono.just(new PermissionUrl("http://minio/upload", "PUT", Map.of())));
    when(this.userStorageRepository.consumeStorage(any(String.class), anyLong())).thenReturn(Mono.just(1L));
    when(this.storageRepository.save(any(StorageObject.class))).thenReturn(Mono.just(saved));

    StepVerifier.create(
            this.service.createUploadSession(new CreateUploadCommand("a.mp4", 1024, MimeType.of("video/mp4"))))
        .assertNext(session -> {
          assertThat(session.uploadId()).isEqualTo("1");
          assertThat(session.storageKey().key()).startsWith("pepe/videos/");
          assertThat(session.uploadUrl()).isEqualTo("http://minio/upload");
          assertThat(session.method()).isEqualTo("PUT");
          assertThat(session.currentStatus()).isEqualTo(StorageSessionStatus.PENDING);
        })
        .verifyComplete();

    verify(this.userStorageRepository).consumeStorage("pepe", 1024L);
  }

  @Test
  void createUploadSessionFailsWhenBucketDoesNotExist() {
    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.userStorageRepository.findByOwnerUsername("pepe")).thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.bucketExists(BucketName.of("movies"))).thenReturn(Mono.just(false));

    StepVerifier.create(
            this.service.createUploadSession(new CreateUploadCommand("a.mp4", 1024, MimeType.of("video/mp4"))))
        .expectError(BucketNotFoundException.class)
        .verify();
  }

  @Test
  void createUploadSessionFailsWhenQuotaExceeded() {
    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.userStorageRepository.findByOwnerUsername("pepe")).thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.bucketExists(BucketName.of("movies"))).thenReturn(Mono.just(true));
    when(this.objectStoragePort.createUploadUrl(any(PresignedUploadRequest.class), any(StorageLocation.class)))
        .thenReturn(Mono.just(new PermissionUrl("http://minio/upload", "PUT", Map.of())));
    when(this.userStorageRepository.consumeStorage(any(String.class), anyLong())).thenReturn(Mono.just(0L));
    when(this.storageRepository.save(any(StorageObject.class)))
        .thenReturn(Mono.just(this.pendingObject(1L)));

    StepVerifier.create(
            this.service.createUploadSession(new CreateUploadCommand("a.mp4", 1024, MimeType.of("video/mp4"))))
        .expectError(ExceededQuotaException.class)
        .verify();
  }

  @Test
  void completeUploadTransitionsToCompletedAndPublishesEvent() {
    StorageObject pending = this.pendingObject(7L);

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(7L)).thenReturn(Mono.just(pending));
    when(this.userStorageRepository.findByOwnerUsername("pepe")).thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.objectExists(any(StorageLocation.class))).thenReturn(Mono.just(true));
    when(this.objectStoragePort.getMetadata(any(StorageLocation.class)))
        .thenReturn(Mono.just(new StorageMetadata("video/mp4", 1024, null, Instant.now())));
    when(this.storageRepository.updateStatus(pending, StorageSessionStatus.PENDING))
        .thenReturn(Mono.just(pending));

    StepVerifier.create(this.service.completeUpload(7L))
        .expectNext(UploadCompletionResult.completed())
        .verifyComplete();

    assertThat(pending.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.COMPLETED);
     verify(this.storageOutbox).append(argThat((StorageIntegrationEvent<?> event) ->
         event instanceof UploadCompletedIntegrationEvent uploadEvent
             && uploadEvent.eventVersion() == 1
             && uploadEvent.aggregateId().equals("7")
             && uploadEvent.payload().storageId().equals(7L)
             && uploadEvent.payload().ownerUsername().equals("pepe")
             && uploadEvent.payload().contentType().equals("video/mp4")
             && uploadEvent.payload().contentLength().equals(1024L)));
    verify(this.eventPublisher).publish(any(UploadCompletedEvent.class));
  }

  @Test
  void completeUploadTreatsRaceAgainstWebhookAsSuccess() {
    StorageObject pending = this.pendingObject(7L);
    StorageObject completed = this.completedObject(7L);

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(7L)).thenReturn(Mono.just(pending));
    when(this.userStorageRepository.findByOwnerUsername("pepe"))
        .thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.objectExists(any(StorageLocation.class)))
        .thenReturn(Mono.just(true));
    when(this.objectStoragePort.getMetadata(any(StorageLocation.class)))
        .thenReturn(Mono.just(new StorageMetadata("video/mp4", 1024, null, Instant.now())));
    when(this.storageRepository.updateStatus(pending, StorageSessionStatus.PENDING))
        .thenReturn(Mono.error(new IllegalStateTransitionException("race")));
    when(this.storageRepository.findById(7L))
        .thenReturn(Mono.just(pending), Mono.just(completed));

    StepVerifier.create(this.service.completeUpload(7L))
        .expectNext(UploadCompletionResult.completed())
        .verifyComplete();

    verify(this.eventPublisher, never()).publish(any(UploadCompletedEvent.class));
  }

  @Test
  void completeUploadReleasesQuotaWhenObjectSizeMismatch() {
    StorageObject pending = this.pendingObject(7L);

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(7L)).thenReturn(Mono.just(pending));
    when(this.userStorageRepository.findByOwnerUsername("pepe")).thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.objectExists(any(StorageLocation.class))).thenReturn(Mono.just(true));
    when(this.objectStoragePort.getMetadata(any(StorageLocation.class)))
        .thenReturn(Mono.just(new StorageMetadata("video/mp4", 42, null, Instant.now())));
    when(this.userStorageRepository.releaseStorage(any(String.class), anyLong()))
        .thenReturn(Mono.just(1L));
    when(this.storageRepository.updateStatus(any(StorageObject.class), any(StorageSessionStatus.class)))
        .thenReturn(Mono.just(pending));

    StepVerifier.create(this.service.completeUpload(7L))
        .expectError(InvalidObjectContentError.class)
        .verify();

     var order = org.mockito.Mockito.inOrder(this.storageOutbox, this.objectStoragePort);
     order.verify(this.storageOutbox).append(any(UploadFailedIntegrationEvent.class));
     order.verify(this.objectStoragePort)
         .delete(new StorageLocation(BucketName.of("movies"), pending.getStorageKey()));
    verify(this.userStorageRepository).releaseStorage("pepe", 1024L);
    verify(this.storageRepository).updateStatus(any(StorageObject.class), any(StorageSessionStatus.class));
    verify(this.eventPublisher).publish(any(UploadFailedEvent.class));
    assertThat(pending.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.FAILED);
  }

  @Test
  void completeUploadByKeyTransitionsAndPublishesEvent() {
    StorageObject pending = this.pendingObject(7L);

    when(this.storageRepository.findByObjectKey("pepe/videos/a.mp4")).thenReturn(Mono.just(pending));
    when(this.userStorageRepository.findByOwnerUsername("pepe")).thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.objectExists(any(StorageLocation.class))).thenReturn(Mono.just(true));
    when(this.objectStoragePort.getMetadata(any(StorageLocation.class)))
        .thenReturn(Mono.just(new StorageMetadata("video/mp4", 1024, null, Instant.now())));
    when(this.storageRepository.updateStatus(pending, StorageSessionStatus.PENDING))
        .thenReturn(Mono.just(pending));

    StepVerifier.create(this.service.completeUploadByKey("pepe/videos/a.mp4")).verifyComplete();

    assertThat(pending.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.COMPLETED);
    verify(this.eventPublisher).publish(any(UploadCompletedEvent.class));
  }

  @Test
  void completeUploadByKeyDoesNotPublishWhenAlreadyCompleted() {
    StorageObject completed = this.completedObject(7L);

    when(this.storageRepository.findByObjectKey("pepe/videos/a.mp4"))
        .thenReturn(Mono.just(completed));
    when(this.userStorageRepository.findByOwnerUsername("pepe")).thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.objectExists(any(StorageLocation.class))).thenReturn(Mono.just(true));
    when(this.objectStoragePort.getMetadata(any(StorageLocation.class)))
        .thenReturn(Mono.just(new StorageMetadata("video/mp4", 1024, null, Instant.now())));

    StepVerifier.create(this.service.completeUploadByKey("pepe/videos/a.mp4")).verifyComplete();

    assertThat(completed.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.COMPLETED);
    verify(this.storageRepository, never()).updateStatus(any(StorageObject.class), any());
    verify(this.eventPublisher, never()).publish(any(UploadCompletedEvent.class));
  }

  @Test
  void completeUploadByKeyReportsFailureAndReleasesQuotaWithoutNotifying() {
    StorageObject pending = this.pendingObject(7L);

    when(this.storageRepository.findByObjectKey("pepe/videos/a.mp4"))
        .thenReturn(Mono.just(pending));
    when(this.userStorageRepository.findByOwnerUsername("pepe"))
        .thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.objectExists(any(StorageLocation.class)))
        .thenReturn(Mono.just(true));
    when(this.objectStoragePort.getMetadata(any(StorageLocation.class)))
        .thenReturn(Mono.just(new StorageMetadata("video/mp4", 42, null, Instant.now())));
    when(this.userStorageRepository.releaseStorage(any(String.class), anyLong()))
        .thenReturn(Mono.just(1L));
    when(this.storageRepository.updateStatus(any(StorageObject.class), any(StorageSessionStatus.class)))
        .thenReturn(Mono.just(pending));

    StepVerifier.create(this.service.completeUploadByKey("pepe/videos/a.mp4")).verifyComplete();

    assertThat(pending.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.FAILED);
    verify(this.objectStoragePort)
        .delete(new StorageLocation(BucketName.of("movies"), pending.getStorageKey()));
    verify(this.userStorageRepository).releaseStorage("pepe", 1024L);
    verify(this.storageRepository).updateStatus(
        any(StorageObject.class), any(StorageSessionStatus.class));
    verify(this.eventPublisher, never()).publish(any(UploadCompletedEvent.class));
  }

  @Test
  void completeUploadByKeyDeletesOrphanObjectWhenSessionExpiredOrFailed() {
    StorageObject expired =
        new StorageObject(
            "pepe",
            new StorageKey("pepe/videos/late.mp4"),
            new StorageMetadata("video/mp4", 1024, null, Instant.now()),
            Instant.now(),
            7L,
            StorageSessionStatus.EXPIRED);

    when(this.storageRepository.findByObjectKey("pepe/videos/late.mp4"))
        .thenReturn(Mono.just(expired));
    when(this.userStorageRepository.findByOwnerUsername("pepe"))
        .thenReturn(Mono.just(PEPE_STORAGE));

    StepVerifier.create(this.service.completeUploadByKey("pepe/videos/late.mp4")).verifyComplete();

    verify(this.objectStoragePort)
        .delete(new StorageLocation(BucketName.of("movies"), new StorageKey("pepe/videos/late.mp4")));
    verify(this.storageRepository, never()).updateStatus(any(StorageObject.class), any());
    verify(this.eventPublisher, never()).publish(any());
  }

  @Test
  void completeUploadByKeyIgnoresObjectsNotRegisteredInDatabase() {
    when(this.storageRepository.findByObjectKey("stray/object.mp4")).thenReturn(Mono.empty());

    StepVerifier.create(this.service.completeUploadByKey("stray/object.mp4")).verifyComplete();

    verify(this.userStorageRepository, never()).findByOwnerUsername(anyString());
    verify(this.eventPublisher, never()).publish(any(UploadCompletedEvent.class));
  }

  @Test
  void completeUploadDefersCompletionWhenObjectIsNotYetInObjectStore() {
    StorageObject pending = this.pendingObject(7L);

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(7L)).thenReturn(Mono.just(pending));
    when(this.userStorageRepository.findByOwnerUsername("pepe")).thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.objectExists(any(StorageLocation.class))).thenReturn(Mono.just(false));

    UploadCompletionResult result = this.service.completeUpload(7L).block();

    assertThat(result.status())
        .isEqualTo(UploadCompletionResult.UploadCompletionStatus.PENDING_VERIFICATION);
    assertThat(pending.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.PENDING);
    verify(this.eventPublisher, never()).publish(any(UploadCompletedEvent.class));
    verify(this.storageRepository, never())
        .updateStatus(any(StorageObject.class), any(StorageSessionStatus.class));
  }
  @Test
  void cancelUploadReleasesQuotaDeletesObjectAndMarksFailed() {
    StorageObject pending = this.pendingObject(7L);

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(7L)).thenReturn(Mono.just(pending));
    when(this.userStorageRepository.findByOwnerUsername("pepe")).thenReturn(Mono.just(PEPE_STORAGE));
    when(this.storageRepository.updateStatus(pending, StorageSessionStatus.PENDING))
        .thenReturn(Mono.just(pending));
    when(this.userStorageRepository.releaseStorage("pepe", 1024L)).thenReturn(Mono.just(1L));

    StepVerifier.create(this.service.cancelUpload(7L)).verifyComplete();

    assertThat(pending.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.FAILED);
    verify(this.objectStoragePort)
        .delete(new StorageLocation(BucketName.of("movies"), pending.getStorageKey()));
    verify(this.userStorageRepository).releaseStorage("pepe", 1024L);
    verify(this.storageRepository).updateStatus(pending, StorageSessionStatus.PENDING);
    verify(this.eventPublisher).publish(any(UploadFailedEvent.class));
  }

  @Test
  void cancelUploadIsNoOpWhenSessionIsNoLongerPending() {
    StorageObject completed = this.completedObject(7L);

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(7L)).thenReturn(Mono.just(completed));

    StepVerifier.create(this.service.cancelUpload(7L)).verifyComplete();

    assertThat(completed.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.COMPLETED);
    verify(this.userStorageRepository, never()).releaseStorage(anyString(), anyLong());
    verify(this.eventPublisher, never()).publish(any());
  }

  private StorageObject objectOwnedBy(long storageId, StorageSessionStatus status, String owner) {
    return new StorageObject(
        owner,
        new StorageKeyGenerator().generate(owner, com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageFolder.from(MimeType.of("video/mp4"))),
        new StorageMetadata("video/mp4", 1024, null, Instant.now()),
        Instant.now(),
        storageId,
        status);
  }

  @Test
  void renewInstructionsReturnsFreshUrlForOwnPendingSession() {
    StorageObject pending = this.pendingObject(9L);

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(9L)).thenReturn(Mono.just(pending));
    when(this.userStorageRepository.findByOwnerUsername("pepe"))
        .thenReturn(Mono.just(PEPE_STORAGE));
    when(this.objectStoragePort.createUploadUrl(any(PresignedUploadRequest.class),
        any(com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageLocation.class)))
        .thenReturn(Mono.just(new PermissionUrl(
            "http://minio/fresh", "PUT", java.util.Map.of())));

    StepVerifier.create(this.service.renewInstructions(9L))
        .assertNext(session -> {
          assertThat(session.uploadId()).isEqualTo("9");
          assertThat(session.uploadUrl()).isEqualTo("http://minio/fresh");
          assertThat(session.method()).isEqualTo("PUT");
          assertThat(session.currentStatus()).isEqualTo(StorageSessionStatus.PENDING);
        })
        .verifyComplete();

    // Renovar NO toca cuota ni estado: la reserva original sigue vigente.
    verify(this.userStorageRepository, never()).consumeStorage(anyString(), anyLong());
    verify(this.storageRepository, never())
        .updateStatus(any(StorageObject.class), any(StorageSessionStatus.class));
  }

  @Test
  void renewInstructionsRejectsNonOwner() {
    StorageObject anasPending = this.objectOwnedBy(9L, StorageSessionStatus.PENDING, "ana");

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(9L)).thenReturn(Mono.just(anasPending));

    StepVerifier.create(this.service.renewInstructions(9L))
        .expectError(
            com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.StorageObjectNotAvailable.class)
        .verify();

    verify(this.objectStoragePort, never())
        .createUploadUrl(any(PresignedUploadRequest.class), any());
  }

  @Test
  void renewInstructionsRejectsNonPendingSession() {
    StorageObject completed = this.completedObject(9L);

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(9L)).thenReturn(Mono.just(completed));

    StepVerifier.create(this.service.renewInstructions(9L))
        .expectError(
            com.guille.media.reproductor.uploader.storage.managedstorage.domain.exception.IllegalStateTransitionException.class)
        .verify();

    verify(this.objectStoragePort, never())
        .createUploadUrl(any(PresignedUploadRequest.class), any());
  }

  @Test
  void cancelUploadRejectsNonOwnerWithoutLeakingExistence() {
    StorageObject anasPendingUpload = this.objectOwnedBy(7L, StorageSessionStatus.PENDING, "ana");

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(7L)).thenReturn(Mono.just(anasPendingUpload));

    StepVerifier.create(this.service.cancelUpload(7L))
        .expectErrorSatisfies(
            error -> assertThat(error).isInstanceOf(StorageObjectNotAvailable.class))
        .verify();

    assertThat(anasPendingUpload.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.PENDING);
    verify(this.userStorageRepository, never()).releaseStorage(anyString(), anyLong());
    verify(this.objectStoragePort, never())
        .delete(any(com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageLocation.class));
    verify(this.eventPublisher, never()).publish(any());
  }

  @Test
  void completeUploadRejectsNonOwnerWithoutLeakingExistence() {
    StorageObject anasPendingUpload = this.objectOwnedBy(7L, StorageSessionStatus.PENDING, "ana");

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(7L)).thenReturn(Mono.just(anasPendingUpload));

    StepVerifier.create(this.service.completeUpload(7L))
        .expectErrorSatisfies(
            error -> assertThat(error).isInstanceOf(StorageObjectNotAvailable.class))
        .verify();

    assertThat(anasPendingUpload.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.PENDING);
    verify(this.userStorageRepository, never()).findByOwnerUsername(anyString());
    verify(this.eventPublisher, never()).publish(any());
  }

  @Test
  void cancelUploadFailsWhenSessionDoesNotExist() {
    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findById(7L)).thenReturn(Mono.empty());

    StepVerifier.create(this.service.cancelUpload(7L))
        .expectError(StorageObjectNotAvailable.class)
        .verify();
  }

  @Test
  void handleObjectRemovedMarksPendingAsFailedAndReleasesQuota() {
    StorageObject pending = this.pendingObject(7L);

    when(this.storageRepository.findByObjectKey(anyString())).thenReturn(Mono.just(pending));
    when(this.userStorageRepository.releaseStorage("pepe", 1024L)).thenReturn(Mono.just(1L));
    when(this.storageRepository.updateStatus(pending, StorageSessionStatus.PENDING))
        .thenReturn(Mono.just(pending));

    StepVerifier.create(this.service.handleObjectRemoved("k1")).verifyComplete();

    assertThat(pending.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.FAILED);
    // El CAS espera PENDING en la fila: es el estado previo real, no el nuevo.
    verify(this.storageRepository).updateStatus(pending, StorageSessionStatus.PENDING);
    // La cuota se libera solo después de ganar el CAS.
    verify(this.userStorageRepository).releaseStorage("pepe", 1024L);
    verify(this.eventPublisher).publish(any(UploadFailedEvent.class));
  }

  @Test
  void handleObjectRemovedDoesNotReleaseQuotaWhenItLosesTheRace() {
    StorageObject pending = this.pendingObject(7L);

    when(this.storageRepository.findByObjectKey(anyString())).thenReturn(Mono.just(pending));
    when(this.storageRepository.updateStatus(pending, StorageSessionStatus.PENDING))
        .thenReturn(Mono.error(new IllegalStateTransitionException("row already COMPLETED")));

    StepVerifier.create(this.service.handleObjectRemoved("k1")).verifyComplete();

    // Perdió la transición (otro hilo completó): no toca cuota ni publica evento.
    verify(this.userStorageRepository, never()).releaseStorage(anyString(), anyLong());
    verify(this.eventPublisher, never()).publish(any());
  }

  @Test
  void handleObjectRemovedIgnoresCompletedObjects() {
    StorageObject completed = this.completedObject(7L);

    when(this.storageRepository.findByObjectKey(anyString())).thenReturn(Mono.just(completed));

    StepVerifier.create(this.service.handleObjectRemoved("k1")).verifyComplete();

    assertThat(completed.getStorageObjectStatus()).isEqualTo(StorageSessionStatus.COMPLETED);
    verify(this.userStorageRepository, never()).releaseStorage(anyString(), anyLong());
    verify(this.eventPublisher, never()).publish(any());
  }

  @Test
  void listUploadsReturnsRecentSessionsOfAuthenticatedUser() {
    StorageObject pending = this.pendingObject(7L);

    when(this.userProvider.getAuthenticatedUser()).thenReturn(Mono.just(PEPE));
    when(this.storageRepository.findRecentByOwner("pepe", 20)).thenReturn(Flux.just(pending));

    StepVerifier.create(this.service.listUploads(20))
        .expectNext(
            new UploadSummary(
                7L,
                pending.getStorageKey().key(),
                StorageSessionStatus.PENDING,
                1024L,
                pending.getCreatedAt()))
        .verifyComplete();
  }

}
