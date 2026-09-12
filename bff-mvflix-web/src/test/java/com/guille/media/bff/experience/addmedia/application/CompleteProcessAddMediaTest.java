package com.guille.media.bff.experience.addmedia.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guille.media.bff.app.dto.CompleteMovieRequest;
import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.app.ports.StorageWebClient;
import com.guille.media.bff.app.dto.MultipartUploadDtos;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import com.guille.media.bff.app.service.WebMoviesService;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaProcessRepository;
import com.guille.media.bff.experience.addmedia.application.DownstreamUnavailableException;
import com.guille.media.bff.experience.addmedia.application.VerdictAppliedException;
import com.guille.media.bff.experience.addmedia.model.AddMediaId;
import com.guille.media.bff.experience.addmedia.model.InvalidAddMediaTransition;
import com.guille.media.bff.experience.addmedia.model.AddMediaProcess;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;
import com.guille.media.bff.experience.addmedia.application.AddMediaResult;
import com.guille.media.bff.infrastructure.persistence.InMemoryAddMediaProcessRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

class CompleteProcessAddMediaTest {

  private final StorageWebClient storage = mock(StorageWebClient.class);
  private final MediaIngestionClient ingestion = mock(MediaIngestionClient.class);
  private final WebMoviesService movies = mock(WebMoviesService.class);
  private final InMemoryAddMediaProcessRepository processes =
      new InMemoryAddMediaProcessRepository();

  private CompleteProcessAddMedia useCase;

  @BeforeEach
  void setUp() {
    this.useCase =
        new CompleteProcessAddMedia(this.processes, this.completion());
  }

  private CompleteAddMedia completion;

  private CompleteAddMedia completion() {
    this.completion = mock(CompleteAddMedia.class);
    return this.completion;
  }

  private String preparedProcess() {
    AddMediaProcess process = AddMediaProcess.starting(AddMediaId.newId(), "pepe")
        .preparing().uploadPrepared(7L, 42L);
    return this.processes.save(process).block().id().value();
  }

  @Test
  void completedOutcomeMarksReadyAndReturnsMovieId() {
    String id = this.preparedProcess();
    MovieDto readyMovie = movie(7L);
    when(this.completion.complete(7L, new CompleteMovieRequest(42L, 1024L)))
        .thenReturn(Mono.just(new UploadCompletionOutcome.Completed(readyMovie)));

    StepVerifier.create(this.useCase.handle("pepe", id, 1024L))
        .assertNext(view -> {
          assertThat(view.phase()).isEqualTo(AddMediaPhase.READY);
          assertThat(view.movieId()).isEqualTo(7L);
        })
        .verifyComplete();
  }

  @Test
  void verifyingOutcomeKeepsProcessVerifyingWithoutRollback() {
    String id = this.preparedProcess();
    when(this.completion.complete(anyLong(), any()))
        .thenReturn(Mono.just(new UploadCompletionOutcome.StillVerifying(42L)));

    StepVerifier.create(this.useCase.handle("pepe", id, null))
        .assertNext(view -> assertThat(view.phase()).isEqualTo(AddMediaPhase.VERIFYING_UPLOAD))
        .verifyComplete();

    verify(this.movies, never()).discardDraft(anyLong());
  }

  @Test
  void definitiveFailureMarksProcessFailedAndPropagates() {
    String id = this.preparedProcess();
    when(this.completion.complete(anyLong(), any()))
        .thenReturn(Mono.error(new VerdictAppliedException(
            "UPLOAD_FAILED", "cuarentena")));

    StepVerifier.create(this.useCase.handle("pepe", id, null))
        .expectError(VerdictAppliedException.class)
        .verify();

    StepVerifier.create(this.processes.findById(new AddMediaId(id)))
        .assertNext(process -> {
          assertThat(process.phase()).isEqualTo(AddMediaPhase.FAILED);
          assertThat(process.failureCode()).isEqualTo("UPLOAD_FAILED");
        })
        .verifyComplete();
  }

  @Test
  void retryAfterVerifyingCompletesWhenUploadFinallyArrives() {
    String id = this.preparedProcess();
    when(this.completion.complete(anyLong(), any()))
        .thenReturn(Mono.just(new UploadCompletionOutcome.StillVerifying(42L)))
        .thenReturn(Mono.just(new UploadCompletionOutcome.Completed(movie(7L))));

    StepVerifier.create(this.useCase.handle("pepe", id, null))
        .assertNext(view -> assertThat(view.phase()).isEqualTo(AddMediaPhase.VERIFYING_UPLOAD))
        .verifyComplete();

    StepVerifier.create(this.useCase.handle("pepe", id, null))
        .assertNext(view -> {
          assertThat(view.phase()).isEqualTo(AddMediaPhase.READY);
          assertThat(view.movieId()).isEqualTo(7L);
        })
        .verifyComplete();
  }

  @Test
  void transientDownstreamFailureKeepsProcessVerifyingForRetry() {
    String id = this.preparedProcess();
    // Movies caído: CompleteAddMedia mapea a DOWNSTREAM_UNAVAILABLE (503),
    // que NO es veredicto definitivo.
    when(this.completion.complete(anyLong(), any()))
        .thenReturn(Mono.error(new DownstreamUnavailableException(
            503, "DOWNSTREAM_UNAVAILABLE", "movies no responde")))
        .thenReturn(Mono.just(new UploadCompletionOutcome.Completed(movie(7L))));

    StepVerifier.create(this.useCase.handle("pepe", id, null))
        .expectError(DownstreamUnavailableException.class)
        .verify();

    // El proceso NO quedó FAILado por un timeout: sigue VERIFYING.
    StepVerifier.create(this.processes.findById(new AddMediaId(id)))
        .assertNext(process -> {
          assertThat(process.phase()).isEqualTo(AddMediaPhase.VERIFYING_UPLOAD);
          assertThat(process.failureCode()).isNull();
        })
        .verifyComplete();

    // El reintento funciona.
    StepVerifier.create(this.useCase.handle("pepe", id, null))
        .assertNext(view -> assertThat(view.phase()).isEqualTo(AddMediaPhase.READY))
        .verifyComplete();
  }

  @Test
  void completeLosesWhenCancelAlreadyClaimed() {
    String id = this.preparedProcess();
    org.assertj.core.api.Assertions.assertThat(
        this.processes.tryCancelClaim(new AddMediaId(id)).block()).isTrue();

    StepVerifier.create(this.useCase.handle("pepe", id, null))
        .expectError(InvalidAddMediaTransition.class)
        .verify();

    verify(this.completion, never()).complete(anyLong(), any());
  }

  @Test
  void readyProcessIsIdempotent() {
    AddMediaId id = AddMediaId.newId();
    this.processes.save(
        AddMediaProcess.starting(id, "pepe").preparing().uploadPrepared(7L, 42L).verifying().ready()).block();

    StepVerifier.create(this.useCase.handle("pepe", id.value(), null))
        .assertNext(view -> assertThat(view.phase()).isEqualTo(AddMediaPhase.READY))
        .verifyComplete();

    verify(this.completion, never()).complete(anyLong(), any());
  }

  @Test
  void multipartCompletesStorageBeforeIngestion() {
    this.useCase = new CompleteProcessAddMedia(this.processes, this.completion(),
        this.ingestion, true, this.storage);
    String id = AddMediaId.newId().value();
    var view = new MediaIngestionClient.MediaIngestionView(id, "pepe", 7L, "multipart-1",
        "AWAITING_UPLOAD", null, null, "pepe/video.mp4", 1024L, "video/mp4",
        "PRESIGNED_MULTIPART", 512L, 2);
    var parts = List.of(new MultipartUploadDtos.CompletedPart(1, "etag-1"),
        new MultipartUploadDtos.CompletedPart(2, "etag-2"));
    when(this.ingestion.status("pepe", id, "corr")).thenReturn(Mono.just(view));
    when(this.storage.completeMultipart("multipart-1", new MultipartUploadDtos.Complete(parts)))
        .thenReturn(Mono.empty());
    when(this.ingestion.complete("pepe", id, 1024L, "corr"))
        .thenReturn(Mono.just(new MediaIngestionClient.MediaIngestionView(id, "pepe", 7L,
            "multipart-1", "COMPLETED", null, null, "pepe/video.mp4", 1024L, "video/mp4",
            "PRESIGNED_MULTIPART", 512L, 2)));

    StepVerifier.create(this.useCase.handleMultipart("pepe", id, 1024L, parts, "corr"))
        .assertNext(result -> assertThat(result.phase()).isEqualTo(AddMediaPhase.READY))
        .verifyComplete();
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(this.storage, this.ingestion);
    order.verify(this.storage).completeMultipart("multipart-1", new MultipartUploadDtos.Complete(parts));
    order.verify(this.ingestion).complete("pepe", id, 1024L, "corr");
  }

  @Test
  void enabledCompletionDelegatesToIngestionWithoutUsingLegacyCompletion() {
    this.useCase = new CompleteProcessAddMedia(this.processes, this.completion(),
        this.ingestion, true, this.storage);
    String id = AddMediaId.newId().value();
    when(this.ingestion.complete("pepe", id, 1024L, "corr"))
        .thenReturn(Mono.just(new MediaIngestionClient.MediaIngestionView(
            id, "pepe", 7L, "42", "COMPLETED", null, null, "pepe/video.mp4", 1024L,
            "video/mp4")));

    StepVerifier.create(this.useCase.handle("pepe", id, 1024L, "corr"))
        .assertNext(result -> assertThat(result.phase()).isEqualTo(AddMediaPhase.READY))
        .verifyComplete();

    verify(this.ingestion).complete("pepe", id, 1024L, "corr");
    verify(this.completion, never()).complete(anyLong(), any());
  }

  @Test
  void foreignOrMissingProcessesAreNotFound() {
    AddMediaId id = AddMediaId.newId();
    this.processes.save(
        AddMediaProcess.starting(id, "ana").preparing().uploadPrepared(1L, 2L)).block();

    StepVerifier.create(this.useCase.handle("pepe", id.value(), null))
        .expectError(com.guille.media.bff.shared.error.EntityNotFound.class)
        .verify();
  }

  private static MovieDto movie(Long id) {
    return new MovieDto(id, "READY", 42L, "PRIVATE", "MOVIE", "Alien", null, 1979,
        List.of(), null, null, null, List.of(), null, null, null, null, null, null, null);
  }
}
