package com.gcorp.service.app.mvflix_playback.application;

import com.gcorp.service.app.mvflix_playback.application.port.AuthorizedCatalog;
import com.gcorp.service.app.mvflix_playback.application.port.ContentAccess;
import com.gcorp.service.app.mvflix_playback.application.port.PlaybackSessionRepository;
import com.gcorp.service.app.mvflix_playback.application.port.WatchProgressRepository;
import com.gcorp.service.app.mvflix_playback.domain.AssetId;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
public class StartPlayback {
  private static final Duration SESSION_TTL = Duration.ofHours(1);

  private final AuthorizedCatalog catalog;
  private final ContentAccess contentAccess;
  private final PlaybackSessionRepository sessions;
  private final WatchProgressRepository progress;
  private final PlaybackOutbox outbox;

  public StartPlayback(AuthorizedCatalog catalog, ContentAccess contentAccess,
      PlaybackSessionRepository sessions, WatchProgressRepository progress, PlaybackOutbox outbox) {
    this.catalog = catalog;
    this.contentAccess = contentAccess;
    this.sessions = sessions;
    this.progress = progress;
    this.outbox = outbox;
  }

  @Transactional("connectionFactoryTransactionManager")
  public Mono<PlaybackStarted> execute(CatalogItemId catalogItemId, ViewerId viewerId,
      String bearerToken) {
    Instant startedAt = Instant.now();
    Instant expiresAt = startedAt.plus(SESSION_TTL);
    return catalog.getPlayableItem(catalogItemId, bearerToken)
        .flatMap(item -> contentAccess.open(item)
            .flatMap(source -> progress.find(viewerId, catalogItemId)
                .defaultIfEmpty(new com.gcorp.service.app.mvflix_playback.domain.WatchProgress(
                    viewerId, catalogItemId))
                .flatMap(watchProgress -> {
              AssetId assetId = item.objectId() == null
                  ? new AssetId(item.asset().id()) : new AssetId(item.objectId());
              PlaybackSession session = PlaybackSession.start(PlaybackSessionId.generate(), viewerId,
                  catalogItemId, assetId, startedAt, expiresAt);
                  Long resume = watchProgress.position() == null || watchProgress.completed()
                      ? null : watchProgress.position().seconds();
                  return sessions.save(session)
                      .flatMap(saved -> outbox.append("PlaybackStarted", saved.id().value(),
                          java.util.Map.of("viewerId", viewerId.value(), "catalogItemId", catalogItemId.value(),
                              "assetId", assetId.value(), "sessionId", saved.id().value()))
                          .thenReturn(new PlaybackStarted(saved, item, source, resume)));
                })));
  }

  public record PlaybackStarted(PlaybackSession session,
      com.gcorp.service.app.mvflix_playback.domain.PlayableCatalogItem item,
      ContentAccess.PlaybackSource source, Long resumePositionSeconds) {}
}
