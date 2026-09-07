package com.gcorp.service.app.mvflix_playback.application;

import com.gcorp.service.app.mvflix_playback.application.port.AuthorizedCatalog;
import com.gcorp.service.app.mvflix_playback.application.port.ContentAccess;
import com.gcorp.service.app.mvflix_playback.application.port.WatchProgressRepository;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.ContentReference;
import com.gcorp.service.app.mvflix_playback.domain.LibraryAssetReference;
import com.gcorp.service.app.mvflix_playback.domain.ManagedObjectReference;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class StartPlayback {
  private static final Duration SESSION_TTL = Duration.ofHours(1);

  private final AuthorizedCatalog catalog;
  private final ContentAccess contentAccess;
  private final WatchProgressRepository progress;
  private final PersistPlaybackSession persistence;

  public StartPlayback(AuthorizedCatalog catalog, ContentAccess contentAccess,
      WatchProgressRepository progress, PersistPlaybackSession persistence) {
    this.catalog = catalog;
    this.contentAccess = contentAccess;
    this.progress = progress;
    this.persistence = persistence;
  }

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
                  ContentReference contentReference = item.objectId() == null
                      ? new LibraryAssetReference(item.asset().id())
                      : new ManagedObjectReference(item.objectId());
                  PlaybackSession session = PlaybackSession.start(PlaybackSessionId.generate(), viewerId,
                      catalogItemId, contentReference, startedAt, expiresAt);
                  Long resume = watchProgress.position() == null || watchProgress.completed()
                      ? null : watchProgress.position().seconds();
                  var payload = new HashMap<String, Object>();
                  payload.put("ownerUsername", viewerId.value());
                  payload.put("movieId", catalogItemId.value());
                  payload.put("mediaId", mediaId(contentReference));
                  payload.put("contentReferenceType", contentReference.type());
                  payload.put("contentReferenceId", contentReference.value());
                  payload.put("sessionId", session.id().value());
                  var metadata = new PlaybackOutbox.EventMetadata(viewerId.value(), viewerId.value(),
                      UUID.randomUUID(), null);
                   return persistence.execute(session, payload, metadata)
                       .map(saved -> new PlaybackStarted(saved, item, source, resume));
                 })));
  }

  private static Long mediaId(ContentReference reference) {
    return reference instanceof LibraryAssetReference local ? local.assetId() : null;
  }

  public record PlaybackStarted(PlaybackSession session,
      com.gcorp.service.app.mvflix_playback.domain.PlayableCatalogItem item,
      ContentAccess.PlaybackSource source, Long resumePositionSeconds) {}
}
