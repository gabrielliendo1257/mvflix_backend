package com.guille.media.bff.experience.addmedia.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.guille.media.bff.app.dto.CreateMovieRequest;
import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.app.dto.UploadCreateRequest;
import com.guille.media.bff.app.dto.UploadSessionDto;
import com.guille.media.bff.app.dto.UserProfile;
import com.guille.media.bff.app.ports.UsersWebPort;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaMovies;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaMovies.IdentifiedDraft;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaStorage;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaProcessRepository;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;
import com.guille.media.bff.experience.addmedia.application.AddMediaResult;
import com.guille.media.bff.experience.addmedia.web.StartAddMediaRequest;
import com.guille.media.bff.infrastructure.persistence.InMemoryAddMediaProcessRepository;
import com.guille.media.bff.experience.addmedia.application.UploadCompletionOutcome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

class StartAddMediaTest {

  private final AddMediaMovies movies = mock(AddMediaMovies.class);
  private final AddMediaStorage storage = mock(AddMediaStorage.class);
  private final UsersWebPort users = mock(UsersWebPort.class);
  private final InMemoryAddMediaProcessRepository processes =
      new InMemoryAddMediaProcessRepository();

  private StartAddMedia useCase;

  @BeforeEach
  void setUp() {
    when(this.users.me()).thenReturn(Mono.just(new UserProfile(
        "u1", "pepe", null, null, "pepe@mvflix.dev", "FREE", true, 0, false)));
    this.useCase = new StartAddMedia(this.movies, this.storage, this.processes, this.users);
  }

  private StartAddMediaRequest request(String idempotencyKey) {
    return new StartAddMediaRequest(
        new StartAddMediaRequest.FileSelection("alien.mp4", 1024L, "video/mp4"),
        new StartAddMediaRequest.MovieSelection(
            348L, new CreateMovieRequest("Alien", null, 1979, List.of(), null, null,
                null, List.of(), null, null, null, null, null, null, "MOVIE")),
        new StartAddMediaRequest.InitialAccess("PRIVATE", List.of()),
        idempotencyKey);
  }

  private UploadSessionDto session() {
    return new UploadSessionDto("42", "http://minio/put", "pepe/videos/a.mp4", "PUT",
        "PENDING", new UploadSessionDto.ExpectedObjectData(1024L, "video/mp4"));
  }

  private MovieDto draft() {
    return new MovieDto(7L, "DRAFT", null, "PRIVATE", "MOVIE", "Alien", null, 1979,
        List.of(), null, null, null, List.of(), null, null, null, null, null, null, null);
  }

  private StartAddMediaCommand command(String key) {
    return request(key).toCommand();
  }

  private Mono<AddMediaResult> start() {
    return this.useCase.handle("pepe", command("intent-1"));
  }

  @Test
  void happyPathCreatesDraftPreparesUploadAndPersistsWaitingForUpload() {
    when(this.movies.createIdentifiedDraft(any(IdentifiedDraft.class))).thenReturn(Mono.just(draft()));
    when(this.storage.prepareUpload(any(UploadCreateRequest.class)))
        .thenReturn(Mono.just(session()));

    StepVerifier.create(start())
        .assertNext(view -> {
          assertThat(view.phase()).isEqualTo(AddMediaPhase.WAITING_FOR_UPLOAD);
          assertThat(view.movieId()).isEqualTo(7L);
           assertThat(view.uploadId()).isEqualTo("42");
          assertThat(view.upload().url()).isEqualTo("http://minio/put");
          assertThat(view.upload().method()).isEqualTo("PUT");
          assertThat(view.upload().expectedSizeBytes()).isEqualTo(1024L);
        })
        .verifyComplete();

    verify(this.movies).createIdentifiedDraft(any(IdentifiedDraft.class));
    verify(this.storage).prepareUpload(any(UploadCreateRequest.class));
    verify(this.movies, never()).discardDraft(any());

    // La intención del usuario llega COMPLETA a Movies: candidato + acceso.
    org.mockito.ArgumentCaptor<IdentifiedDraft> captor =
        org.mockito.ArgumentCaptor.forClass(IdentifiedDraft.class);
    verify(this.movies, org.mockito.Mockito.atLeastOnce())
        .createIdentifiedDraft(captor.capture());
    assertThat(captor.getValue().tmdbId()).isEqualTo(348L);
    assertThat(captor.getValue().visibility()).isEqualTo("PRIVATE");
  }

  @Test
  void ingestionPathRejectsBlockedUserBeforeDelegating() {
    var ingestion = mock(MediaIngestionClient.class);
    var blocked = new UserProfile(
        "u1", "pepe", null, null, "pepe@mvflix.dev", "FREE", true, 3, true);
    when(this.users.me()).thenReturn(Mono.just(blocked));
    var enabled = new StartAddMedia(
        this.movies, this.storage, this.processes, this.users, null, ingestion, true);

    StepVerifier.create(enabled.handle("pepe", command("blocked"), "corr"))
        .expectError(UserBlockedException.class)
        .verify();

    verifyNoInteractions(ingestion);
  }

  @Test
  void replayWithSameIdempotencyKeyReturnsSameProcessWithoutSideEffects() {
    when(this.movies.createIdentifiedDraft(any(IdentifiedDraft.class))).thenReturn(Mono.just(draft()));
    when(this.storage.prepareUpload(any(UploadCreateRequest.class)))
        .thenReturn(Mono.just(session()));
    // El replay renueva las instrucciones en vez de devolver upload=null.
    when(this.storage.refreshInstructions(42L))
        .thenReturn(Mono.just(new UploadSessionDto("42", "http://minio/fresh",
            "pepe/videos/a.mp4", "PUT", "PENDING",
            new UploadSessionDto.ExpectedObjectData(1024L, "video/mp4"))));

    AddMediaResult first = start().block();
    AddMediaResult replay = start().block();

    assertThat(replay.addMediaId()).isEqualTo(first.addMediaId());
     assertThat(replay.uploadId()).isEqualTo("42");
    assertThat(replay.phase()).isEqualTo(AddMediaPhase.WAITING_FOR_UPLOAD);
    assertThat(replay.upload().url()).isEqualTo("http://minio/fresh");
    // Un solo draft y una sola sesión de upload para el mismo intento.
    verify(this.movies, org.mockito.Mockito.times(1)).createIdentifiedDraft(any(IdentifiedDraft.class));
    verify(this.storage, org.mockito.Mockito.times(1)).prepareUpload(any());
  }

  @Test
  void concurrentStartsWithSameKeyCreateSideEffectsOnlyOnce() {
    when(this.movies.createIdentifiedDraft(any(IdentifiedDraft.class))).thenAnswer(
        inv -> Mono.delay(java.time.Duration.ofMillis(50)).thenReturn(draft()));
    when(this.storage.prepareUpload(any(UploadCreateRequest.class)))
        .thenReturn(Mono.just(session()));

    Mono<AddMediaResult> v1 = start();
    Mono<AddMediaResult> v2 = start();
    StepVerifier.create(Mono.zip(v1, v2))
        .assertNext(tuple -> {
          // Uno gana y termina WAITING_FOR_UPLOAD; el otro ve PREPARING o
          // WAITING_FOR_UPLOAD según el timing de la persistencia final.
          assertThat(tuple.getT1().phase()).isIn(
              AddMediaPhase.WAITING_FOR_UPLOAD, AddMediaPhase.PREPARING);
          assertThat(tuple.getT2().phase()).isIn(
              AddMediaPhase.WAITING_FOR_UPLOAD, AddMediaPhase.PREPARING);
        })
        .verifyComplete();

    verify(this.movies, org.mockito.Mockito.times(1)).createIdentifiedDraft(any(IdentifiedDraft.class));
    verify(this.storage, org.mockito.Mockito.times(1)).prepareUpload(any());
  }

  @Test
  void loserOfTheClaimSeesPreparingWithoutSideEffects() {
    // Proceso existente ya reclamado por otro request en vuelo.
    this.processes.createIfAbsent("pepe", "intent-1",
        com.guille.media.bff.experience.addmedia.application.StartAddMedia
            .fingerprintOf(request("intent-1").toCommand())).block();
    String id = this.processes.createIfAbsent("pepe", "intent-1",
        com.guille.media.bff.experience.addmedia.application.StartAddMedia
            .fingerprintOf(request("intent-1").toCommand())).block().id().value();
    org.assertj.core.api.Assertions.assertThat(
        this.processes.tryClaim(com.guille.media.bff.experience.addmedia.model.AddMediaId
            .parse(id)).block()).isTrue();

    StepVerifier.create(start())
        .assertNext(view -> {
          assertThat(view.phase()).isEqualTo(AddMediaPhase.PREPARING);
          assertThat(view.movieId()).isNull();
        })
        .verifyComplete();

    verify(this.movies, never()).createIdentifiedDraft(any(IdentifiedDraft.class));
    verify(this.storage, never()).prepareUpload(any());
  }

  @Test
  void storageFailureDiscardsOnlyThisProcessDraftAndPropagates() {
    when(this.movies.createIdentifiedDraft(any(IdentifiedDraft.class))).thenReturn(Mono.just(draft()));
    when(this.movies.discardDraft(7L)).thenReturn(Mono.empty());
    when(this.storage.prepareUpload(any(UploadCreateRequest.class)))
        .thenReturn(Mono.error(new RuntimeException("storage unavailable")));

    StepVerifier.create(start())
        .expectErrorMessage("storage unavailable")
        .verify();

    // Compensación acotada: solo el draft de este intento.
    verify(this.movies).discardDraft(7L);
    // El proceso queda en STARTING: un replay con la misma key reintenta limpio.
    this.processes.createIfAbsent("pepe", "intent-1",
        com.guille.media.bff.experience.addmedia.application.StartAddMedia
            .fingerprintOf(request("intent-1").toCommand()))
        .as(StepVerifier::create)
        .assertNext(process -> {
          assertThat(process.phase()).isEqualTo(AddMediaPhase.STARTING);
          assertThat(process.movieId()).isNull();
        })
        .verifyComplete();
  }

  @Test
  void failedDraftCompensationIsDurableAndIdempotent() {
    when(this.movies.createIdentifiedDraft(any(IdentifiedDraft.class))).thenReturn(Mono.just(draft()));
    when(this.storage.prepareUpload(any(UploadCreateRequest.class)))
        .thenReturn(Mono.error(new RuntimeException("storage unavailable")));
    when(this.movies.discardDraft(7L)).thenReturn(Mono.error(new RuntimeException("movies unavailable")));

    StepVerifier.create(start()).expectErrorMessage("storage unavailable").verify();

    var task = this.processes.claimPending(10).blockFirst();
    assertThat(task.kind()).isEqualTo(
        com.guille.media.bff.experience.addmedia.application.port.AddMediaCompensationRepository.Kind.DISCARD_DRAFT);
    assertThat(task.resourceId()).isEqualTo(7L);
    this.processes.enqueue(task.processId(), task.kind(), task.resourceId(), new RuntimeException("again")).block();
    assertThat(this.processes.claimPending(10).count().block()).isEqualTo(1L);
  }

  @Test
  void invalidStorageUploadIdAbortsWithoutPersisting() {
    when(this.movies.createIdentifiedDraft(any(IdentifiedDraft.class))).thenReturn(Mono.just(draft()));
    when(this.movies.discardDraft(7L)).thenReturn(Mono.empty());
    when(this.storage.prepareUpload(any(UploadCreateRequest.class)))
        .thenReturn(Mono.just(new UploadSessionDto("no-es-numerico", "http://minio/put",
            "k.mp4", "PUT", "PENDING",
            new UploadSessionDto.ExpectedObjectData(1024L, "video/mp4"))));

    StepVerifier.create(start())
        .expectErrorSatisfies(error -> {
          assertThat(error).isInstanceOf(InvalidStorageResponseException.class);
          assertThat(error.getMessage()).contains("no-es-numerico");
        })
        .verify();

    // Respuesta no fiable => nada se persiste ni se compensa upload inexistente.
    verify(this.storage, never()).cancelUpload(anyLong());
    verify(this.movies).discardDraft(7L);
  }

  @Test
  void persistenceFailureAfterUploadCreatedCompensatesBothResources() {
    com.guille.media.bff.experience.addmedia.application.port.AddMediaProcessRepository failingSave =
        new com.guille.media.bff.infrastructure.persistence.InMemoryAddMediaProcessRepository() {
          @Override
          public Mono<com.guille.media.bff.experience.addmedia.model.AddMediaProcess> save(
              com.guille.media.bff.experience.addmedia.model.AddMediaProcess process) {
            if (process.phase() == AddMediaPhase.WAITING_FOR_UPLOAD) {
              return Mono.error(new RuntimeException("db down"));
            }
            return super.save(process);
          }
        };
    StartAddMedia useCaseFailing =
        new StartAddMedia(this.movies, this.storage, failingSave, this.users);

    when(this.users.me()).thenReturn(Mono.just(new UserProfile(
        "u1", "pepe", null, null, "pepe@mvflix.dev", "FREE", true, 0, false)));
    when(this.movies.createIdentifiedDraft(any(IdentifiedDraft.class))).thenReturn(Mono.just(draft()));
    when(this.storage.prepareUpload(any(UploadCreateRequest.class)))
        .thenReturn(Mono.just(session()));
    when(this.storage.cancelUpload(42L)).thenReturn(Mono.empty());
    when(this.movies.discardDraft(7L)).thenReturn(Mono.empty());

    StepVerifier.create(useCaseFailing.handle("pepe", request("intent-x").toCommand()))
        .expectErrorMessage("db down")
        .verify();

    // Upload cancelado (cuota liberada) y draft descartado: cero huérfanos.
    verify(this.storage).cancelUpload(42L);
    verify(this.movies).discardDraft(7L);
  }

  @Test
  void sameKeyWithDifferentPayloadIsRejectedAsIdempotencyConflict() {
    when(this.movies.createIdentifiedDraft(any(IdentifiedDraft.class))).thenReturn(Mono.just(draft()));
    when(this.storage.prepareUpload(any(UploadCreateRequest.class)))
        .thenReturn(Mono.just(session()));
    start().block();

    // Misma key, OTRO archivo: conflicto explícito, no replay silencioso.
    StartAddMediaRequest other = new StartAddMediaRequest(
        new StartAddMediaRequest.FileSelection("OTRO.mp4", 9999L, "video/mp4"),
        new StartAddMediaRequest.MovieSelection(348L, request("intent-1").movie().draft()),
        new StartAddMediaRequest.InitialAccess("PRIVATE", List.of()),
        "intent-1");

    StepVerifier.create(this.useCase.handle("pepe", other.toCommand()))
        .expectError(com.guille.media.bff.experience.addmedia.application.
            IdempotencyConflictException.class)
        .verify();
  }

  @Test
  void omittedAccessDefaultsToExplicitPrivate() {
    when(this.movies.createIdentifiedDraft(any(IdentifiedDraft.class)))
        .thenReturn(Mono.just(draft()));
    when(this.storage.prepareUpload(any(UploadCreateRequest.class)))
        .thenReturn(Mono.just(session()));

    var baseReq = request("intent-2");
    StartAddMediaRequest withoutAccess = new StartAddMediaRequest(
        new StartAddMediaRequest.FileSelection(baseReq.file().filename(),
            baseReq.file().sizeBytes(), baseReq.file().mimeType()),
        request("intent-2").movie(),
        null,
        "intent-2");

    StepVerifier.create(this.useCase.handle("pepe", withoutAccess.toCommand()))
        .assertNext(view -> assertThat(view.phase())
            .isEqualTo(AddMediaPhase.WAITING_FOR_UPLOAD))
        .verifyComplete();

    org.mockito.ArgumentCaptor<IdentifiedDraft> captor =
        org.mockito.ArgumentCaptor.forClass(IdentifiedDraft.class);
    verify(this.movies).createIdentifiedDraft(captor.capture());
    assertThat(captor.getValue().visibility()).isEqualTo("PRIVATE");
  }

  @Test
  void blockedUserSurfacesForbiddenWithoutPreparingUpload() {
    when(this.users.me()).thenReturn(Mono.just(new UserProfile(
        "u1", "pepe", null, null, "pepe@mvflix.dev", "FREE", true, 5, true)));

    StepVerifier.create(start())
        .expectError(UserBlockedException.class)
        .verify();

    verify(this.storage, never()).prepareUpload(any());
    verify(this.movies, never()).discardDraft(any());
  }
}
