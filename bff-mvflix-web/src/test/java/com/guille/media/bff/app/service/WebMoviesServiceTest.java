package com.guille.media.bff.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.guille.media.bff.app.dto.BulkVisibilityRequest;
import com.guille.media.bff.app.dto.BulkVisibilityResultDto;
import com.guille.media.bff.app.dto.MovieDetailDto;
import com.guille.media.bff.app.dto.MediaAssetDto;
import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.app.dto.StreamingSessionDto;
import com.guille.media.bff.app.ports.MoviesWebClient;
import com.guille.media.bff.app.ports.StorageWebClient;
import com.guille.media.bff.app.ports.UsersWebPort;
import com.guille.media.bff.infrastructure.http.StoragePlaybackTokenProvider;
import com.guille.media.bff.app.service.WebSessionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

class WebMoviesServiceTest {

  private final MoviesWebClient moviesWebClient = mock(MoviesWebClient.class);
  private final StorageWebClient storageWebClient = mock(StorageWebClient.class);
  private final UsersWebPort usersWebPort = mock(UsersWebPort.class);
  private final StoragePlaybackTokenProvider playbackTokenProvider = mock(StoragePlaybackTokenProvider.class);
  private final WebClient playbackWebClient = mock(WebClient.class);
  private final WebSessionService webSessionService = mock(WebSessionService.class);

  @org.junit.jupiter.api.BeforeEach
  void setUpSession() {
    org.mockito.Mockito.lenient()
        .when(webSessionService.currentSubject())
        .thenReturn(reactor.core.publisher.Mono.just("pepe"));
  }
  private final StreamTicketService streamTicketService = new StreamTicketService("test-secret", 300);
  private JobStore jobStore;
  private WebMoviesService service;

  @BeforeEach
  void setUp() {
    this.jobStore = new JobStore();
    this.service = new WebMoviesService(this.moviesWebClient, this.storageWebClient,
        this.usersWebPort, this.streamTicketService, this.jobStore,
        this.webSessionService,
        null,
        null);
  }

  private static MovieDto movie(Long id, Long objectId) {
    return new MovieDto(id, "READY", objectId, "PRIVATE", "MOVIE", "The Colossus of Rhodes", null, 1961,
        List.of("Adventure"), 3.2, "2h 7m", "Sergio Leone", List.of("Rory Calhoun"),
        "Overview...", null, "1961-06-15", "Italy", "Italian", null, "ENRICHED");
  }

  private static MediaAssetDto asset(Long movieId) {
    return new MediaAssetDto(1L, 10L, "/movies/a.mp4", 1024L, "video/mp4", "IDENTIFIED", movieId);
  }

  private static List<Long> ids(int from, int count) {
    return java.util.stream.IntStream.range(from, count).mapToObj(Long::valueOf).toList();
  }

  @Test
  void detailWithObjectIdReturnsPlaybackUrl() {
    when(moviesWebClient.movieById(1L)).thenReturn(Mono.just(movie(1L, 42L)));
    when(storageWebClient.stream("42"))
        .thenReturn(Mono.just(new StreamingSessionDto("42", "http://minio/42?sig=abc",
            null, "2026-01-01T00:00:00Z", "GET")));

    StepVerifier.create(service.detail(1L))
        .assertNext(detail -> {
          assertThat(detail.movie().id()).isEqualTo(1L);
          assertThat(detail.playback().available()).isTrue();
          assertThat(detail.playback().url()).isEqualTo("http://minio/42?sig=abc");
        })
        .verifyComplete();

    verify(storageWebClient).stream("42");
  }

  @Test
  void detailWithoutObjectIdTriesLocalPlaybackAndDegradesWhenNoAsset() {
    when(moviesWebClient.movieById(1L)).thenReturn(Mono.just(movie(1L, null)));
    when(moviesWebClient.assetByMovie(1L))
        .thenReturn(Mono.error(new RuntimeException("sin asset de biblioteca")));

    StepVerifier.create(service.detail(1L))
        .assertNext(detail -> {
          assertThat(detail.movie().id()).isEqualTo(1L);
          assertThat(detail.playback().available()).isFalse();
          assertThat(detail.playback().url()).isNull();
        })
        .verifyComplete();

    verify(storageWebClient, never()).stream(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void libraryMoviePlaybackPointsToBffStreamProxy() {
    when(moviesWebClient.movieById(1L)).thenReturn(Mono.just(movie(1L, null)));
    when(moviesWebClient.assetByMovie(1L))
        .thenReturn(Mono.just(new com.guille.media.bff.app.dto.MediaAssetDto(
            5L, 7L, "Interstellar (2014).mkv", 100, "video/x-matroska", "IDENTIFIED", 1L)));

    StepVerifier.create(service.detail(1L))
        .assertNext(detail -> {
          assertThat(detail.playback().available()).isTrue();
          assertThat(detail.playback().url()).isEqualTo("/web/movies/1/stream");
        })
        .verifyComplete();

    verify(storageWebClient, never()).stream(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void streamForwardsRangeToStorage() {
    when(moviesWebClient.assetByMovie(1L))
        .thenReturn(Mono.just(new com.guille.media.bff.app.dto.MediaAssetDto(
            5L, 7L, "Interstellar (2014).mkv", 100, "video/x-matroska", "IDENTIFIED", 1L)));
    when(storageWebClient.streamLibraryFile(eq(7L), eq("Interstellar (2014).mkv"), any()))
        .thenReturn(Mono.just(org.springframework.http.ResponseEntity.ok().build()));

    StepVerifier.create(service.stream(1L, "bytes=0-99", null))
        .expectNextCount(1)
        .verifyComplete();

    verify(storageWebClient).streamLibraryFile(7L, "Interstellar (2014).mkv", "bytes=0-99");
  }

  @Test
  void streamWithoutAssetIsNotFound() {
    when(moviesWebClient.assetByMovie(1L)).thenReturn(Mono.empty());

    StepVerifier.create(service.stream(1L, null, null))
        .expectNextMatches(response -> response.getStatusCode().is4xxClientError())
        .verifyComplete();
  }

  @Test
  void streamWithDeniedAccessIsForbidden() {
    when(moviesWebClient.assetByMovie(1L))
        .thenReturn(Mono.error(new WebClientResponseException(
            403, "Forbidden", org.springframework.http.HttpHeaders.EMPTY, null, null)));

    StepVerifier.create(service.stream(1L, null, null))
        .expectError(WebClientResponseException.class)
        .verify();
  }

  @Test
  void visibilityPublicIgnoresUsernames() {
    when(moviesWebClient.updateMovieAccess(1L, "PUBLIC", List.of("Maria")))
        .thenReturn(Mono.just(movie(1L, null)));

    StepVerifier.create(service.visibility(1L, "PUBLIC", List.of("Maria")))
        .expectNextCount(1)
        .verifyComplete();

    verify(moviesWebClient).updateMovieAccess(1L, "PUBLIC", List.of("Maria"));
    verify(moviesWebClient, never()).updateVisibility(anyLong(), anyString());
    verify(moviesWebClient, never()).updateShares(anyLong(), anyList());
  }

  @Test
  void visibilitySharedUsesSingleAtomicCall() {
    when(moviesWebClient.updateMovieAccess(1L, "SHARED", List.of("Maria")))
        .thenReturn(Mono.just(movie(1L, null)));

    StepVerifier.create(service.visibility(1L, "SHARED", List.of("Maria")))
        .expectNextCount(1)
        .verifyComplete();

    // Una sola llamada transaccional: nunca visibilidad sin shares.
    verify(moviesWebClient).updateMovieAccess(1L, "SHARED", List.of("Maria"));
    verify(moviesWebClient, never()).updateVisibility(anyLong(), anyString());
    verify(moviesWebClient, never()).updateShares(anyLong(), anyList());
  }

  @Test
  void sharedWithoutUsernamesIsMoviesDecisionNotLocalValidation() {
    // La regla de si SHARED exige usuarios vive en movies; el BFF no duplica.
    when(moviesWebClient.updateMovieAccess(1L, "SHARED", List.of()))
        .thenReturn(Mono.just(movie(1L, null)));

    StepVerifier.create(service.visibility(1L, "SHARED", List.of()))
        .expectNextCount(1)
        .verifyComplete();

    verify(moviesWebClient).updateMovieAccess(1L, "SHARED", List.of());
  }

  @Test
  void visibilityBlankFails() {
    StepVerifier.create(service.visibility(1L, "", null))
        .expectError(ResponseStatusException.class)
        .verify();

    verifyNoInteractions(moviesWebClient);
  }

  @Test
  void bulkVisibilityLanzaJobYEmiteProgreso() {
    when(moviesWebClient.bulkUpdateVisibility(anyList(), anyList(), eq("PUBLIC"), any(), any()))
        .thenReturn(Mono.just(new BulkVisibilityResultDto(2, 2, 0)));

    Job initial = service.bulkVisibility(
        new BulkVisibilityRequest(List.of(1L, 2L), List.of(), "PUBLIC", null)).block();
    assertThat(initial.status()).isEqualTo(JobStatus.RUNNING);

    Job finalState = this.jobStore.events(initial.id()).last().block();
    assertThat(finalState.status()).isEqualTo(JobStatus.COMPLETED);
    assertThat(finalState.total()).isEqualTo(2);
    assertThat(finalState.done()).isEqualTo(2);

    verify(moviesWebClient).bulkUpdateVisibility(
        List.of(1L, 2L), List.of(), "PUBLIC", null, "");
  }

  @Test
  void bulkVisibilityResuelveIdsDeLibrerias() {
    when(moviesWebClient.listAssets(10L, null))
        .thenReturn(Flux.just(asset(11L), asset(12L)));
    when(moviesWebClient.bulkUpdateVisibility(anyList(), anyList(), eq("PUBLIC"), any(), any()))
        .thenReturn(Mono.just(new BulkVisibilityResultDto(2, 2, 0)));

    Job initial = service.bulkVisibility(
        new BulkVisibilityRequest(List.of(), List.of(10L), "PUBLIC", null)).block();

    Job finalState = this.jobStore.events(initial.id()).last().block();
    assertThat(finalState.done()).isEqualTo(2);

    verify(moviesWebClient).listAssets(10L, null);
    verify(moviesWebClient).bulkUpdateVisibility(
        List.of(11L, 12L), List.of(), "PUBLIC", null, "");
  }

  @Test
  void bulkVisibilityTroceaEnLotes() {
    when(moviesWebClient.bulkUpdateVisibility(anyList(), anyList(), eq("PUBLIC"), any(), any()))
        .thenAnswer(invocation -> {
          List<Long> chunk = invocation.getArgument(0);
          return Mono.just(new BulkVisibilityResultDto(chunk.size(), chunk.size(), 0));
        });

    Job initial = service.bulkVisibility(
        new BulkVisibilityRequest(ids(0, 60), List.of(), "PUBLIC", null)).block();

    Job finalState = this.jobStore.events(initial.id()).last().block();
    assertThat(finalState.total()).isEqualTo(60);
    assertThat(finalState.done()).isEqualTo(60);

    verify(moviesWebClient, org.mockito.Mockito.times(3))
        .bulkUpdateVisibility(anyList(), anyList(), eq("PUBLIC"), any(), any());
  }

  @Test
  void bulkVisibilitySharedSinUsernamesFalla() {
    StepVerifier.create(service.bulkVisibility(
            new BulkVisibilityRequest(List.of(1L), List.of(), "SHARED", List.of())))
        .expectError(ResponseStatusException.class)
        .verify();

    verifyNoInteractions(moviesWebClient);
  }

  @Test
  void bulkVisibilityBlankFalla() {
    StepVerifier.create(service.bulkVisibility(
            new BulkVisibilityRequest(List.of(1L), List.of(), "", null)))
        .expectError(ResponseStatusException.class)
        .verify();

    verifyNoInteractions(moviesWebClient);
  }

  @Test
  void sharesForwardsToMovies() {
    when(moviesWebClient.updateShares(1L, List.of("Maria")))
        .thenReturn(Mono.just(movie(1L, null)));

    StepVerifier.create(service.shares(1L, List.of("Maria")))
        .expectNextCount(1)
        .verifyComplete();

    verify(moviesWebClient).updateShares(1L, List.of("Maria"));
  }

  @Test
  void detailDegradesGracefullyWhenStorageIsDown() {
    when(moviesWebClient.movieById(1L)).thenReturn(Mono.just(movie(1L, 42L)));
    when(storageWebClient.stream("42"))
        .thenReturn(Mono.error(new WebClientRequestException(
            new IllegalStateException("conexión rechazada"),
            org.springframework.http.HttpMethod.POST,
            java.net.URI.create("http://storage/streaming"),
            org.springframework.http.HttpHeaders.EMPTY)));

    StepVerifier.create(service.detail(1L))
        .assertNext(detail -> {
          assertThat(detail.movie().id()).isEqualTo(1L);
          assertThat(detail.playback().available()).isFalse();
          assertThat(detail.playback().url()).isNull();
        })
        .verifyComplete();
  }

  @Test
  void previewReturnsCandidateMetadataWithoutPersisting() {
    when(moviesWebClient.previewCandidate(43020L))
        .thenReturn(Mono.just(new com.guille.media.bff.app.dto.MovieEnrichmentPreviewDto(
            "The Colossus of Rhodes", "Il colosso di Rodi", 1961,
            List.of("Adventure"), 3.2, "2h 7m", "Sergio Leone", List.of("Rory Calhoun"),
            "Overview...", null, "1961-06-15", "Italy", "Italian", 43020L)));

    StepVerifier.create(service.preview(43020L))
        .assertNext(preview -> {
          assertThat(preview.title()).isEqualTo("The Colossus of Rhodes");
          assertThat(preview.tmdbId()).isEqualTo(43020L);
          assertThat(preview.director()).isEqualTo("Sergio Leone");
        })
        .verifyComplete();

    verify(moviesWebClient).previewCandidate(43020L);
  }

  @Test
  void streamWithValidTicketProxiesLibraryFile() {
    String ticket = this.streamTicketService.issue(7L, "user-jwt-value");
    when(moviesWebClient.assetByMovie(7L))
        .thenReturn(Mono.just(new MediaAssetDto(1L, 3L, "Blade Runner 2049.mkv",
            2097152, "video/x-matroska", "IDENTIFIED", 7L)));
    when(storageWebClient.streamLibraryFile(3L, "Blade Runner 2049.mkv", "bytes=0-1023"))
        .thenReturn(Mono.just(org.springframework.http.ResponseEntity.<org.springframework.core.io.buffer.DataBuffer>status(206).build()));

    StepVerifier.create(service.stream(7L, "bytes=0-1023", ticket))
        .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(206))
        .verifyComplete();
  }

  @Test
  void streamWithTicketOfAnotherMovieIsRejected() {
    String ticket = this.streamTicketService.issue(7L, "user-jwt-value");

    StepVerifier.create(service.stream(8L, "bytes=0-1023", ticket))
        .expectError(StreamTicketException.class)
        .verify();
  }

  @Test
  void streamWithExpiredTicketIsRejected() {
    StreamTicketService shortLived = new StreamTicketService("test-secret", -1);
    String ticket = shortLived.issue(7L, "user-jwt-value");

    StepVerifier.create(service.stream(7L, "bytes=0-1023", ticket))
        .expectError(StreamTicketException.class)
        .verify();
  }

  @Test
  void streamWithoutTicketUsesSecurityContext() {
    when(moviesWebClient.assetByMovie(7L))
        .thenReturn(Mono.just(new MediaAssetDto(1L, 3L, "Blade Runner 2049.mkv",
            2097152, "video/x-matroska", "IDENTIFIED", 7L)));
    when(storageWebClient.streamLibraryFile(3L, "Blade Runner 2049.mkv", "bytes=0-1023"))
        .thenReturn(Mono.just(org.springframework.http.ResponseEntity.<org.springframework.core.io.buffer.DataBuffer>status(206).build()));

    StepVerifier.create(service.stream(7L, "bytes=0-1023", null))
        .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(206))
        .verifyComplete();
  }

  @Test
  void issueStreamTicketWithUserJwtReturnsTicketForVisibleMovie() {
    when(moviesWebClient.movieById(4L)).thenReturn(Mono.just(movie(4L, null)));

    StepVerifier.create(service.issueStreamTicket(4L, "session-access-token"))
        .assertNext(ticket -> {
          assertThat(ticket.url()).startsWith("/web/movies/4/stream?ticket=");
          String resolvedJwt = this.streamTicketService.resolve(ticket.url().split("ticket=")[1]).userJwt();
          assertThat(resolvedJwt).isEqualTo("session-access-token");
        })
        .verifyComplete();
  }

  @Test
  void issueStreamTicketWithoutUserJwtIsRejected() {
    StepVerifier.create(service.issueStreamTicket(4L, null))
        .expectError(StreamTicketException.class)
        .verify();
    StepVerifier.create(service.issueStreamTicket(4L, " "))
        .expectError(StreamTicketException.class)
        .verify();
  }

  @Test
  void updateMovieDelegatesToMoviesBackend() {
    var request = new com.guille.media.bff.app.dto.MovieUpdateRequest(
        "Dune: Part Two", null, 2024, List.of("Sci-Fi"), null, null, null, null,
        null, "2024-03-01", null, null, null, null, null);
    when(moviesWebClient.updateMovie(4L, request))
        .thenReturn(Mono.just(movie(4L, null)));

    StepVerifier.create(service.updateMovie(4L, request))
        .assertNext(updated -> assertThat(updated.id()).isEqualTo(4L))
        .verifyComplete();

    verify(moviesWebClient).updateMovie(4L, request);
  }
}
