package com.guille.media.bff.experience.playback.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.guille.media.bff.app.ports.StorageWebClient;
import com.guille.media.bff.app.service.WebSessionService;
import com.guille.media.bff.experience.playback.application.AssetNotPlayableException;
import com.guille.media.bff.experience.playback.application.DirectSource;
import com.guille.media.bff.experience.playback.application.PlaybackForbiddenException;
import com.guille.media.bff.experience.playback.application.PlaybackMediaNotFoundException;
import com.guille.media.bff.experience.playback.application.PlaybackSourceUnavailableException;
import com.guille.media.bff.experience.playback.application.StartPlayback;
import com.guille.media.bff.infrastructure.security.AnonymousViewerIdentity;
import com.guille.media.bff.experience.playback.application.port.LocalPlaybackAccess;
import com.guille.media.bff.experience.playback.application.port.PlaybackCatalog;
import com.guille.media.bff.experience.playback.application.port.PlaybackService;
import com.guille.media.bff.experience.playback.infrastructure.http.HmacLocalPlaybackAccess;
import com.guille.media.bff.presenter.api.ApiExceptionHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

class PlaybackControllerTest {

  private final StorageWebClient storage = mock(StorageWebClient.class);
  private final WebSessionService session = mock(WebSessionService.class);
  private final HmacLocalPlaybackAccess localAccess =
      new HmacLocalPlaybackAccess("test-secret", Duration.ofHours(2));

  private WebTestClient client;
  private PlaybackService playbackService;
  private PlaybackCatalog catalog;

  @BeforeEach
  void setUp() {
    this.catalog = mock(PlaybackCatalog.class);
    this.playbackService = mock(PlaybackService.class);
    when(this.session.currentSubject()).thenReturn(Mono.just("pepe"));
    var controller = new PlaybackController(
        new StartPlayback(this.catalog, this.playbackService, this.localAccess),
        this.localAccess,
        this.storage,
        this.playbackService,
         new AnonymousViewerIdentity(this.session, this.playbackService));
    this.client = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private void catalogReturns(String status, Long objectId, boolean withAsset) {
    var asset = !withAsset ? null : new com.guille.media.bff.experience.playback.application.PlayableAsset(
        5L, 42L, "video/x-matroska", 2048L, null, 3L, "Movies/edward.mkv");
    when(this.catalog.loadVisibleMedia(42L)).thenReturn(Mono.just(
        new PlaybackCatalog.PlaybackMedia(
            new PlaybackCatalog.PlaybackMovie(
                42L, "Edward Scissorhands", status, "/poster.jpg", "1h 45m", objectId),
            asset)));
  }

  @Test
  void startManagedReturnsExperienceContractWithoutStorageDetails() {
    this.catalogReturns("READY", 77L, true);
    Instant expiresAt = Instant.now().plus(Duration.ofHours(3));
    when(this.playbackService.start(42L)).thenReturn(Mono.just(new PlaybackService.StartedSession(
        "123e4567-e89b-12d3-a456-426614174000",
        new DirectSource("https://minio.dev:9000/bucket/key?X-Amz-Signature=abc", expiresAt,
            "video/x-matroska"), null)));

    this.client.post()
        .uri("/web/playback/42/session")
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.sessionId").isNotEmpty()
        .jsonPath("$.media.id").isEqualTo(42)
        .jsonPath("$.media.title").isEqualTo("Edward Scissorhands")
        .jsonPath("$.playback.strategy").isEqualTo("DIRECT")
        .jsonPath("$.playback.url").value(url ->
            assertThat((String) url).startsWith("https://minio.dev:9000/"))
        .jsonPath("$.playback.expiresAt").isNotEmpty()
        .jsonPath("$.resume").isEmpty();
  }

  @Test
  void startLocalReturnsProxyCapabilityUrl() {
    this.catalogReturns("READY", null, true);
    when(this.playbackService.start(42L, "pepe")).thenReturn(Mono.just(
        new PlaybackService.StartedSession("123e4567-e89b-12d3-a456-426614174000", null, null)));

    this.client.post()
        .uri("/web/playback/42/session")
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.playback.url").value(url ->
            assertThat((String) url).startsWith("/web/playback/assets/5/stream?token="))
        .jsonPath("$.playback.mimeType").isEqualTo("video/x-matroska");
  }

  @Test
  void forbiddenMapsTo403() {
    when(this.catalog.loadVisibleMedia(42L))
        .thenReturn(Mono.error(new PlaybackForbiddenException(42L)));

    this.client.post()
        .uri("/web/playback/42/session")
        .exchange()
        .expectStatus().isForbidden()
        .expectBody()
        .jsonPath("$.error").isEqualTo("PLAYBACK_FORBIDDEN");
  }

  @Test
  void missingMediaMapsTo404() {
    when(this.catalog.loadVisibleMedia(42L))
        .thenReturn(Mono.error(new PlaybackMediaNotFoundException(42L)));

    this.client.post()
        .uri("/web/playback/42/session")
        .exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.error").isEqualTo("MEDIA_NOT_FOUND");
  }

  @Test
  void notReadyMediaMapsTo409WithCode() {
    this.catalogReturns("DRAFT", null, true);

    this.client.post()
        .uri("/web/playback/42/session")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.error").isEqualTo("MEDIA_NOT_READY");
  }

  @Test
  void storageFailureMapsTo503() {
    this.catalogReturns("READY", 77L, true);
    when(this.playbackService.start(42L)).thenReturn(Mono.error(
        new PlaybackSourceUnavailableException("storage no disponible",
            new IllegalStateException("down"))));

    this.client.post()
        .uri("/web/playback/42/session")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        .expectBody()
        .jsonPath("$.error").isEqualTo("SOURCE_UNAVAILABLE");
  }

  @Test
  void localStreamRequiresCapability() {
    this.client.get()
        .uri("/web/playback/assets/5/stream")
        .exchange()
        .expectStatus().isUnauthorized()
        .expectBody()
        .jsonPath("$.error").isEqualTo("STREAM_ACCESS_INVALID");
  }

  @Test
  void localStreamRejectsCapabilityOfAnotherAsset() throws Exception {
    LocalPlaybackAccess.MintedAccess minted = this.localAccess.mint(
        new LocalPlaybackAccess.LocalMintCommand(42L, 6L, 3L, "Movies/other.mkv", "pepe"))
        .block(Duration.ofSeconds(1));

    this.client.get()
        .uri("/web/playback/assets/5/stream?token=" + minted.rawToken())
        .exchange()
        .expectStatus().isUnauthorized();
  }

  @Test
  void localStreamProxiesRangeWith206Passthrough() throws Exception {
    LocalPlaybackAccess.MintedAccess minted = this.localAccess.mint(
        new LocalPlaybackAccess.LocalMintCommand(42L, 5L, 3L, "Movies/edward.mkv", "pepe"))
        .block(Duration.ofSeconds(1));
    HttpHeaders downstream = new HttpHeaders();
    downstream.set(HttpHeaders.CONTENT_RANGE, "bytes 0-1023/2048");
    downstream.setContentType(org.springframework.http.MediaType.parseMediaType("video/x-matroska"));
    when(this.storage.streamLibraryFile(3L, "Movies/edward.mkv", "bytes=0-1023"))
        .thenReturn(Mono.just(ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
            .headers(downstream)
            .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap("chunk".getBytes())))));

    this.client.get()
        .uri("/web/playback/assets/5/stream?token=" + minted.rawToken())
        .header(HttpHeaders.RANGE, "bytes=0-1023")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.PARTIAL_CONTENT)
        .expectHeader().valueEquals(HttpHeaders.CONTENT_RANGE, "bytes 0-1023/2048")
        .expectBody()
        .consumeWith(result -> assertThat(result.getResponseBody()).isNotEmpty());
  }
}
