package com.guille.media.bff.experience.playback.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.guille.media.bff.experience.playback.application.port.LocalPlaybackAccess;
import com.guille.media.bff.experience.playback.application.port.PlaybackCatalog;
import com.guille.media.bff.experience.playback.application.port.PlaybackService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

class StartPlaybackTest {

  private final PlaybackCatalog catalog = mock(PlaybackCatalog.class);
  private final PlaybackService playbackService = mock(PlaybackService.class);
  private final LocalPlaybackAccess localAccess = mock(LocalPlaybackAccess.class);
  private final StartPlayback useCase =
      new StartPlayback(this.catalog, this.playbackService, this.localAccess);

  @BeforeEach
  void resetStubs() {
    org.mockito.Mockito.reset(this.catalog, this.playbackService, this.localAccess);
  }

  private static PlaybackCatalog.PlaybackMedia media(String status, Long objectId,
      PlayableAsset asset) {
    return new PlaybackCatalog.PlaybackMedia(
        new PlaybackCatalog.PlaybackMovie(42L, "Edward Scissorhands", status,
            "/poster.jpg", "1h 45m", objectId),
        asset);
  }

  private static PlayableAsset libraryAsset() {
    return new PlayableAsset(5L, 42L, "video/x-matroska", 2048L, null, 3L, "Movies/edward.mkv");
  }

  @Test
  void authorizedReadyManagedReturnsPresignedDirectSourceWithoutCatalogAsset() {
    Instant expiresAt = Instant.now().plus(Duration.ofHours(3));
    // Los objetos subidos no generan MediaAsset: MANAGED se resuelve por objectId.
    org.mockito.Mockito.when(this.catalog.loadVisibleMedia(42L))
        .thenReturn(Mono.just(media("READY", 77L, null)));
    org.mockito.Mockito.when(this.playbackService.start(42L))
        .thenReturn(Mono.just(new PlaybackService.StartedSession(
            "123e4567-e89b-12d3-a456-426614174000",
            new DirectSource("https://minio.dev:9000/bucket/key?X-Amz-Signature=abc", expiresAt, null),
            null)));
    verifyNoInteractions(this.localAccess);

    StepVerifier.create(this.useCase.handle("pepe", 42L))
        .assertNext(session -> {
          assertThat(session.sessionId()).isNotNull();
          assertThat(session.strategy()).isEqualTo(PlaybackStrategy.DIRECT);
          assertThat(session.source().url()).startsWith("https://minio.dev:9000/");
          assertThat(session.source().expiresAt()).isEqualTo(expiresAt);
          assertThat(session.resumePosition()).isNull();
        })
        .verifyComplete();
  }

  @Test
  void managedPlaybackPreservesServiceSessionId() {
    Instant expiresAt = Instant.now().plus(Duration.ofHours(3));
    org.mockito.Mockito.when(this.catalog.loadVisibleMedia(42L))
        .thenReturn(Mono.just(media("READY", 77L, null)));
    org.mockito.Mockito.when(this.playbackService.start(42L))
        .thenReturn(Mono.just(new PlaybackService.StartedSession(
            "123e4567-e89b-12d3-a456-426614174000",
            new DirectSource("https://minio/key", expiresAt, null), null)));

    StepVerifier.create(this.useCase.handle("pepe", 42L).zipWith(this.useCase.handle("pepe", 42L)))
         .assertNext(pair -> assertThat(pair.getT1().sessionId())
             .isEqualTo(pair.getT2().sessionId()))
        .verifyComplete();
  }

  @Test
  void authorizedReadyLocalMintsCapabilityForProxyUrl() {
    Instant expiresAt = Instant.now().plus(Duration.ofHours(2));
    org.mockito.Mockito.when(this.catalog.loadVisibleMedia(42L))
        .thenReturn(Mono.just(media("READY", null, libraryAsset())));
    org.mockito.Mockito.when(this.localAccess.mint(
            new LocalPlaybackAccess.LocalMintCommand(
                42L, 5L, 3L, "Movies/edward.mkv", "pepe")))
        .thenReturn(Mono.just(new LocalPlaybackAccess.MintedAccess("jwt-token", expiresAt)));
    org.mockito.Mockito.when(this.playbackService.start(42L, "pepe"))
        .thenReturn(Mono.just(new PlaybackService.StartedSession(
            "123e4567-e89b-12d3-a456-426614174000", null, null)));

    StepVerifier.create(this.useCase.handle("pepe", 42L))
        .assertNext(session -> {
          assertThat(session.source().url())
              .isEqualTo("/web/playback/assets/5/stream?token=jwt-token");
          assertThat(session.source().mimeType()).isEqualTo("video/x-matroska");
          assertThat(session.source().expiresAt()).isEqualTo(expiresAt);
        })
        .verifyComplete();
  }

  @Test
  void forbiddenPropagatesWithoutResolvingSource() {
    org.mockito.Mockito.when(this.catalog.loadVisibleMedia(42L))
        .thenReturn(Mono.error(new PlaybackForbiddenException(42L)));

    StepVerifier.create(this.useCase.handle("pepe", 42L))
        .expectError(PlaybackForbiddenException.class)
        .verify();
    verifyNoInteractions(this.playbackService, this.localAccess);
  }

  @Test
  void missingMediaPropagates404Semantics() {
    org.mockito.Mockito.when(this.catalog.loadVisibleMedia(99L))
        .thenReturn(Mono.error(new PlaybackMediaNotFoundException(99L)));

    StepVerifier.create(this.useCase.handle("pepe", 99L))
        .expectError(PlaybackMediaNotFoundException.class)
        .verify();
  }

  @Test
  void draftMediaIsConflictNotMissingNorFailure() {
    org.mockito.Mockito.when(this.catalog.loadVisibleMedia(42L))
        .thenReturn(Mono.just(media("DRAFT", null, libraryAsset())));

    StepVerifier.create(this.useCase.handle("pepe", 42L))
        .expectErrorSatisfies(error -> {
          assertThat(error).isInstanceOf(AssetNotPlayableException.class);
          assertThat(((AssetNotPlayableException) error).getCode())
              .isEqualTo(StartPlayback.CODE_MEDIA_NOT_READY);
        })
        .verify();
    verifyNoInteractions(this.playbackService, this.localAccess);
  }

  @Test
  void readyWithoutAssetIsNoPlayableAsset() {
    org.mockito.Mockito.when(this.catalog.loadVisibleMedia(42L))
        .thenReturn(Mono.just(media("READY", null, null)));

    StepVerifier.create(this.useCase.handle("pepe", 42L))
        .expectErrorSatisfies(error -> {
          assertThat(error).isInstanceOf(AssetNotPlayableException.class);
          assertThat(((AssetNotPlayableException) error).getCode())
              .isEqualTo(StartPlayback.CODE_NO_PLAYABLE_ASSET);
        })
        .verify();
  }

  @Test
  void storageFailureSurfacesAsSourceUnavailable() {
    org.mockito.Mockito.when(this.catalog.loadVisibleMedia(42L))
        .thenReturn(Mono.just(media("READY", 77L, null)));
    org.mockito.Mockito.when(this.playbackService.start(42L))
        .thenReturn(Mono.error(new PlaybackSourceUnavailableException(
            "storage no disponible para playback",
            new IllegalStateException("connection refused"))));

    StepVerifier.create(this.useCase.handle("pepe", 42L))
        .expectError(PlaybackSourceUnavailableException.class)
        .verify();
  }
}
