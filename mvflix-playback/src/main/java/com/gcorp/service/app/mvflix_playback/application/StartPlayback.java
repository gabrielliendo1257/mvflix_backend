package com.gcorp.service.app.mvflix_playback.application;

import com.gcorp.service.app.mvflix_playback.application.port.AuthorizedCatalog;
import com.gcorp.service.app.mvflix_playback.application.port.ContentAccess;
import com.gcorp.service.app.mvflix_playback.application.port.PlaybackSessionRepository;
import com.gcorp.service.app.mvflix_playback.domain.AssetId;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class StartPlayback {
  private static final Duration SESSION_TTL = Duration.ofHours(1);

  private final AuthorizedCatalog catalog;
  private final ContentAccess contentAccess;
  private final PlaybackSessionRepository sessions;

  public StartPlayback(AuthorizedCatalog catalog, ContentAccess contentAccess,
      PlaybackSessionRepository sessions) {
    this.catalog = catalog;
    this.contentAccess = contentAccess;
    this.sessions = sessions;
  }

  public Mono<PlaybackStarted> execute(CatalogItemId catalogItemId, ViewerId viewerId,
      String bearerToken) {
    Instant startedAt = Instant.now();
    Instant expiresAt = startedAt.plus(SESSION_TTL);
    return catalog.getPlayableItem(catalogItemId, bearerToken)
        .flatMap(item -> contentAccess.open(item)
            .flatMap(source -> {
              AssetId assetId = item.objectId() == null
                  ? new AssetId(item.asset().id()) : new AssetId(item.objectId());
              PlaybackSession session = PlaybackSession.start(PlaybackSessionId.generate(), viewerId,
                  catalogItemId, assetId, startedAt, expiresAt);
              return sessions.save(session)
                  .map(saved -> new PlaybackStarted(saved, item, source, null));
            }));
  }

  public record PlaybackStarted(PlaybackSession session,
      com.gcorp.service.app.mvflix_playback.domain.PlayableCatalogItem item,
      ContentAccess.PlaybackSource source, Long resumePositionSeconds) {}
}
