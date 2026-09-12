package com.guille.media.bff.experience.addmedia.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.guille.media.bff.app.dto.MovieEnrichmentPreviewDto;
import com.guille.media.bff.app.dto.MovieEnrichmentSearchDto;
import com.guille.media.bff.app.dto.UploadSessionDto;
import com.guille.media.bff.infrastructure.persistence.InMemoryAddMediaProcessRepository;
import com.guille.media.bff.app.ports.MoviesWebClient;
import com.guille.media.bff.app.ports.StorageWebClient;
import com.guille.media.bff.app.ports.UsersWebPort;
import com.guille.media.bff.infrastructure.http.MoviesAddMediaAdapter;
import com.guille.media.bff.infrastructure.http.StorageAddMediaAdapter;
import com.guille.media.bff.app.service.WebSessionService;
import com.guille.media.bff.experience.addmedia.application.CancelAddMedia;
import com.guille.media.bff.experience.addmedia.application.CompleteProcessAddMedia;
import com.guille.media.bff.experience.addmedia.application.GetAddMediaStatus;
import com.guille.media.bff.experience.addmedia.application.PreviewMovieCandidate;
import com.guille.media.bff.experience.addmedia.application.SearchMovieCandidates;
import com.guille.media.bff.experience.addmedia.application.StartAddMedia;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaProcessRepository;

import org.mockito.ArgumentMatchers;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AddMediaControllerTest {

  private final MoviesWebClient moviesWebClient = mock(MoviesWebClient.class);
  private final StorageWebClient storageWebClient = mock(StorageWebClient.class);
  private final InMemoryAddMediaProcessRepository processes =
      new InMemoryAddMediaProcessRepository();
  private final WebSessionService session = mock(WebSessionService.class);
  private final com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient ingestion =
      mock(com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient.class);

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    when(this.session.currentSubject()).thenReturn(Mono.just("pepe"));
     MoviesAddMediaAdapter moviesAdapter = new MoviesAddMediaAdapter(this.moviesWebClient);
     StorageAddMediaAdapter storageAdapter = new StorageAddMediaAdapter(this.storageWebClient);
     UsersWebPort users = mock(UsersWebPort.class);
     when(users.me()).thenReturn(Mono.just(new com.guille.media.bff.app.dto.UserProfile(
         "u1", "pepe", null, null, "pepe@mvflix.dev", "FREE", true, 0, false)));
     when(this.ingestion.create(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyString()))
         .thenReturn(Mono.just(new com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient.MediaIngestionView(
             "00000000-0000-0000-0000-000000000004", "pepe", 7L, "42", "AWAITING_UPLOAD",
             null, "http://minio/put", "pepe/videos/a.mp4", 1024L, "video/mp4")));
     CompleteProcessAddMedia completeProcess =
         new CompleteProcessAddMedia(this.ingestion);
     GetAddMediaStatus getStatus = new GetAddMediaStatus(this.ingestion);
    AddMediaController controller =
        new AddMediaController(
            new SearchMovieCandidates(moviesAdapter),
            new PreviewMovieCandidate(moviesAdapter),
             new StartAddMedia(users, this.ingestion),
            completeProcess,
             new CancelAddMedia(this.ingestion),
            getStatus,
            this.session);
    this.client = WebTestClient.bindToController(controller)
        .controllerAdvice(new com.guille.media.bff.presenter.api.ApiExceptionHandler())
        .build();
  }

  @Test
  void startReturnsCreatedWithUploadInstructions() {
        when(this.moviesWebClient.createIdentifiedDraft(
            ArgumentMatchers.any(), ArgumentMatchers.eq(348L), ArgumentMatchers.any(),
            ArgumentMatchers.any(), ArgumentMatchers.anyString()))
        .thenReturn(Mono.just(new com.guille.media.bff.app.dto.MovieDto(7L, "DRAFT", null,
            "PRIVATE", "MOVIE", "Alien", null, 1979, List.of(), null, null, null,
            List.of(), null, null, null, null, null, null, null)));
    when(this.storageWebClient.createUpload(ArgumentMatchers.any()))
        .thenReturn(Mono.just(new UploadSessionDto("42", "http://minio/put",
            "pepe/videos/a.mp4", "PUT", "PENDING",
            new UploadSessionDto.ExpectedObjectData(1024L, "video/mp4"))));

    String startBody = "{"
        + "\"file\": {\"filename\": \"alien.mp4\", \"sizeBytes\": 1024, "
        + "\"mimeType\": \"video/mp4\"},"
        + "\"movie\": {\"providerId\": 348, \"draft\": {\"title\": \"Alien\"}},"
        + "\"access\": {\"visibility\": \"PRIVATE\"},"
        + "\"idempotencyKey\": \"k-123\"}";

    this.client
        .post()
        .uri("/web/add-media")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .bodyValue(startBody)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.phase").isEqualTo("WAITING_FOR_UPLOAD")
        .jsonPath("$.movieId").isEqualTo(7)
        .jsonPath("$.upload.url").isEqualTo("http://minio/put");
  }

  @Test
  void startRejectsAnOversizedIdempotencyHeader() {
    String startBody = "{"
        + "\"file\": {\"filename\": \"alien.mp4\", \"sizeBytes\": 1024, "
        + "\"mimeType\": \"video/mp4\"},"
        + "\"movie\": {\"providerId\": 348, \"draft\": {\"title\": \"Alien\"}},"
        + "\"idempotencyKey\": \"k-123\"}";

    this.client
        .post()
        .uri("/web/add-media")
        .header("Idempotency-Key", "x".repeat(129))
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .bodyValue(startBody)
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void statusRestoresFreshUploadInstructionsWhileWaiting() {
     String pid = "11111111-1111-1111-1111-111111111111";
     when(this.ingestion.status("pepe", pid, "add-media:" + pid))
         .thenReturn(Mono.just(new com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient.MediaIngestionView(
             pid, "pepe", 7L, "42", "AWAITING_UPLOAD", null, "http://minio/fresh", "k.mp4",
             1024L, "video/mp4")));

    this.client
        .get()
         .uri("/web/add-media/" + pid)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.upload.url").isEqualTo("http://minio/fresh");
  }

  @Test
  void statusIsOwnerScopedAndHidesForeignProcesses() {
    when(this.session.currentSubject()).thenReturn(Mono.just("pepe"));
        when(this.moviesWebClient.createIdentifiedDraft(
            ArgumentMatchers.any(), ArgumentMatchers.eq(348L), ArgumentMatchers.any(),
            ArgumentMatchers.any(), ArgumentMatchers.anyString()))
        .thenReturn(Mono.just(new com.guille.media.bff.app.dto.MovieDto(7L, "DRAFT", null,
            "PRIVATE", "MOVIE", "Alien", null, 1979, List.of(), null, null, null,
            List.of(), null, null, null, null, null, null, null)));
    when(this.storageWebClient.createUpload(ArgumentMatchers.any()))
        .thenReturn(Mono.just(new UploadSessionDto("42", "http://minio/put",
            "k.mp4", "PUT", "PENDING",
            new UploadSessionDto.ExpectedObjectData(1024L, "video/mp4"))));
    // El GET de estado renueva instrucciones mientras esté WAITING_FOR_UPLOAD.
     when(this.ingestion.status("pepe", "00000000-0000-0000-0000-000000000004",
         "add-media:00000000-0000-0000-0000-000000000004"))
         .thenReturn(Mono.just(new com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient.MediaIngestionView(
             "00000000-0000-0000-0000-000000000004", "pepe", 7L, "42", "AWAITING_UPLOAD", null,
             "http://minio/fresh", "k.mp4", 1024L, "video/mp4")));
    String startBody = "{"
        + "\"file\": {\"filename\": \"alien.mp4\", \"sizeBytes\": 1024, "
        + "\"mimeType\": \"video/mp4\"},"
        + "\"movie\": {\"providerId\": 348, \"draft\": {\"title\": \"Alien\"}},"
        + "\"idempotencyKey\": \"k-77\"}";
    String addMediaId = this.client.post().uri("/web/add-media")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .bodyValue(startBody).exchange().expectStatus().isCreated()
        .expectBody(com.guille.media.bff.experience.addmedia.application.AddMediaResult.class)
        .returnResult().getResponseBody().addMediaId();

    // El dueño lo ve.
    this.client.get().uri("/web/add-media/" + addMediaId).exchange()
        .expectStatus().isOk()
        .expectBody().jsonPath("$.addMediaId").isEqualTo(addMediaId);

     // Otro usuario: 404 sin filtrar existencia.
     when(this.session.currentSubject()).thenReturn(Mono.just("ana"));
     when(this.ingestion.status("ana", addMediaId, "add-media:" + addMediaId))
         .thenReturn(Mono.error(new com.guille.media.bff.shared.error.EntityNotFound("Proceso no encontrado")));
    this.client.get().uri("/web/add-media/" + addMediaId).exchange()
        .expectStatus().isNotFound();
  }

  @Test
  void candidatesForwardsQueryAndYear() {
    when(this.moviesWebClient.searchCandidates("Alien", 1979))
        .thenReturn(Flux.just(new MovieEnrichmentSearchDto(348L, "Alien", 1979, "poster.png", "overview", "/enrich")));

    this.client
        .get()
        .uri("/web/add-media/candidates?query=Alien&year=1979")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].tmdb_id")
        .isEqualTo(348);
  }

  @Test
  void candidatePreviewForwardsProviderId() {
    when(this.moviesWebClient.previewCandidate(348L))
        .thenReturn(Mono.just(new MovieEnrichmentPreviewDto("Alien", "Alien", 1979,
            java.util.List.of("Horror"), 8.0, "1h 57m", "Ridley Scott",
            java.util.List.of("Sigourney Weaver"), "Overview...", "poster.png",
            "1979-05-25", "UK", "English", 348L)));

    this.client
        .get()
        .uri("/web/add-media/candidates/348")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.tmdb_id")
        .isEqualTo(348);
  }
}
