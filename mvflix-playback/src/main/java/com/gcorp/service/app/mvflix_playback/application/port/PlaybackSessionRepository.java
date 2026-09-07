package com.gcorp.service.app.mvflix_playback.application.port;

import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import reactor.core.publisher.Mono;

public interface PlaybackSessionRepository {
  Mono<PlaybackSession> findById(PlaybackSessionId id);
  Mono<PlaybackSession> save(PlaybackSession session);
}
