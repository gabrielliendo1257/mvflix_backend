package com.gcorp.service.app.mvflix_playback.application.port;

import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import reactor.core.publisher.Mono;

public interface PlaybackSessionRepository {
  Mono<PlaybackSession> findById(PlaybackSessionId id);
  Mono<PlaybackSession> findActive(
      com.gcorp.service.app.mvflix_playback.domain.ViewerId viewerId,
      com.gcorp.service.app.mvflix_playback.domain.CatalogItemId catalogItemId);
  Mono<PlaybackSession> create(PlaybackSession session);
  Mono<PlaybackSession> save(PlaybackSession session);
}
