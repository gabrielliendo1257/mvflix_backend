package com.guille.media.bff.experience.playback.application;

import com.guille.media.bff.experience.playback.application.port.LocalPlaybackAccess;
import com.guille.media.bff.experience.playback.application.port.PlaybackCatalog;
import com.guille.media.bff.experience.playback.application.port.PlaybackService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * "Quiero reproducir este contenido ahora."
 *
 * <p>Orquestación ligera de lecturas: autorización y resolución viven en los
 * dueños (movies aplica visibilidad, storage valida el objeto); aquí solo se
 * compone la experiencia. Sin Saga: no hay mutaciones distribuidas que
 * compensar. El resultado es stateless: {@code sessionId} existe para
 * correlación (logs/progreso futuro), no se persiste.
 *
 * <p>Seam de resume: {@code resumePosition} queda a {@code null} hasta que
 * exista un dueño de watch history; el contrato HTTP ya reserva el campo.
 */
@Slf4j
@Service
public class StartPlayback {

  static final String STATUS_READY = "READY";
  static final String CODE_MEDIA_NOT_READY = "MEDIA_NOT_READY";
  static final String CODE_NO_PLAYABLE_ASSET = "NO_PLAYABLE_ASSET";

  private final PlaybackCatalog catalog;
  private final PlaybackService playbackService;
  private final LocalPlaybackAccess localAccess;

  public StartPlayback(
      PlaybackCatalog catalog,
      PlaybackService playbackService,
      LocalPlaybackAccess localAccess) {
    this.catalog = catalog;
    this.playbackService = playbackService;
    this.localAccess = localAccess;
  }

  public Mono<PlaybackSession> handle(String subject, long mediaId) {
    var visibleMedia = this.catalog.loadVisibleMedia(mediaId, subject);
    if (visibleMedia == null) visibleMedia = this.catalog.loadVisibleMedia(mediaId);
    return visibleMedia
        .flatMap(media -> this.requirePlayable(mediaId, media))
        .flatMap(resolved -> this.openSource(subject, mediaId, resolved)
            .map(opened -> this.compose(resolved, opened)));
  }

  /**
   * Estado del contenido: movies ya decidió qué significa READY/DRAFT con su
   * propio modelo; la experiencia solo exige que esté publicado y con locator.
   * Un MANAGED no necesita MediaAsset de catálogo (el objeto subido aún no lo
   * genera); un LOCAL sí.
   */
  private Mono<Resolved> requirePlayable(long mediaId, PlaybackCatalog.PlaybackMedia media) {
    if (!STATUS_READY.equals(media.movie().status())) {
      return Mono.error(new AssetNotPlayableException(
          CODE_MEDIA_NOT_READY, "La media " + mediaId + " aún no está lista"));
    }
    if (media.movie().objectId() == null && media.asset() == null) {
      return Mono.error(new AssetNotPlayableException(
          CODE_NO_PLAYABLE_ASSET, "No hay contenido reproducible para la media " + mediaId));
    }
    return Mono.just(new Resolved(media.movie(), media.asset()));
  }

  /** MANAGED: presigned directo al object store. LOCAL: capability del proxy del BFF. */
  private Mono<OpenedSource> openSource(String subject, long mediaId, Resolved playable) {
    var movie = playable.movie();
    if (movie.objectId() != null) {
      var started = this.playbackService.start(mediaId, subject);
      // Keeps older in-process adapters usable while the viewer-aware port rolls out.
      if (started == null) started = this.playbackService.start(mediaId);
      return started
          .map(session -> new OpenedSource(
              UUID.fromString(session.sessionId()), session.source(), session.resumePositionSeconds() == null
                  ? null : Duration.ofSeconds(session.resumePositionSeconds())));
    }
    var asset = playable.asset();
    return this.localAccess
        .mint(new LocalPlaybackAccess.LocalMintCommand(
            asset.mediaId(), asset.assetId(), asset.libraryId(),
            asset.relativePath(), subject))
        .map(minted -> new OpenedSource(
            UUID.randomUUID(),
            new DirectSource(
                "/web/playback/assets/" + asset.assetId() + "/stream?token=" + minted.rawToken(),
                minted.expiresAt(),
                asset.mimeType()),
            null));
  }

  private PlaybackSession compose(Resolved resolved, OpenedSource opened) {
    var movie = resolved.movie();
    var session = new PlaybackSession(
        opened.sessionId(),
        movie.id(),
        movie.title(),
        movie.posterPath(),
        movie.duration(),
        PlaybackStrategy.DIRECT,
        opened.source(),
        opened.resumePosition());
    // Sin URLs firmadas ni tokens en logs: solo identificadores de correlación.
    log.info("playback session started: sessionId={} media={} storage={} strategy=DIRECT",
        session.sessionId(), movie.id(),
        movie.objectId() != null ? "MANAGED object=" + movie.objectId() : "LOCAL asset=" + resolved.asset().assetId());
    return session;
  }

  private record Resolved(PlaybackCatalog.PlaybackMovie movie, PlayableAsset asset) {}

  private record OpenedSource(UUID sessionId, DirectSource source, Duration resumePosition) {}
}
