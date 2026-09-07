package com.gcorp.service.app.mvflix_playback.application;

import com.gcorp.service.app.mvflix_playback.application.port.PlaybackSessionRepository;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackPosition;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class RecordPlaybackProgress {
  private final PlaybackSessionRepository sessions;

  public RecordPlaybackProgress(PlaybackSessionRepository sessions) {
    this.sessions = sessions;
  }

  public Mono<PlaybackSession> execute(PlaybackSessionId sessionId, ViewerId viewerId,
      long sequence, long positionSeconds, Long durationSeconds, boolean completed) {
    return sessions.findById(sessionId)
        .switchIfEmpty(Mono.error(new PlaybackSessionNotFoundException(sessionId)))
        .flatMap(session -> validateOwner(session, viewerId))
        .flatMap(session -> {
          var position = new PlaybackPosition(positionSeconds, durationSeconds);
          if (completed) {
            session.complete(position);
          } else {
            session.recordProgress(position, sequence);
          }
          return sessions.save(session);
        });
  }

  private Mono<PlaybackSession> validateOwner(PlaybackSession session, ViewerId viewerId) {
    if (!session.viewerId().equals(viewerId)) {
      return Mono.error(new PlaybackSessionForbiddenException());
    }
    return Mono.just(session);
  }

  public static final class PlaybackSessionNotFoundException extends RuntimeException {
    public PlaybackSessionNotFoundException(PlaybackSessionId id) {
      super("Playback session not found: " + id.value());
    }
  }

  public static final class PlaybackSessionForbiddenException extends RuntimeException {
    public PlaybackSessionForbiddenException() {
      super("Playback session belongs to another viewer");
    }
  }
}
