package com.gcorp.service.app.mvflix_playback.application;

import com.gcorp.service.app.mvflix_playback.application.port.PlaybackSessionRepository;
import com.gcorp.service.app.mvflix_playback.application.port.WatchProgressRepository;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackPosition;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
public class RecordPlaybackProgress {
  private final PlaybackSessionRepository sessions;
  private final WatchProgressRepository progress;
  private final PlaybackOutbox outbox;

  public RecordPlaybackProgress(PlaybackSessionRepository sessions, WatchProgressRepository progress,
      PlaybackOutbox outbox) {
    this.sessions = sessions;
    this.progress = progress;
    this.outbox = outbox;
  }

  @Transactional("connectionFactoryTransactionManager")
  public Mono<PlaybackSession> execute(PlaybackSessionId sessionId, ViewerId viewerId,
      long sequence, long positionSeconds, Long durationSeconds, boolean completed) {
    return sessions.findById(sessionId)
        .switchIfEmpty(Mono.error(new PlaybackSessionNotFoundException(sessionId)))
        .flatMap(session -> validateOwner(session, viewerId))
        .flatMap(session -> {
          var position = new PlaybackPosition(positionSeconds, durationSeconds);
          var sessionChanged = completed
              ? session.complete(position, sequence)
              : session.recordProgress(position, sequence);
          if (!sessionChanged) {
            return Mono.just(session);
          }
          return progress.find(viewerId, session.catalogItemId())
              .defaultIfEmpty(new com.gcorp.service.app.mvflix_playback.domain.WatchProgress(
                  viewerId, session.catalogItemId()))
              .flatMap(watch -> {
                var watchChanged = watch.update(position, session.id(), session.startedAt(), sequence, completed,
                    java.time.Instant.now());
                if (!watchChanged) {
                  return Mono.just(session);
                }
                return sessions.save(session)
                    .flatMap(saved -> progress.save(watch)
                        .then(outbox.append(completed ? "PlaybackCompleted" : "PlaybackProgressed",
                            saved.id().value(), java.util.Map.of("viewerId", viewerId.value(),
                                "catalogItemId", saved.catalogItemId().value(), "assetId", saved.assetId().value(),
                                "sessionId", saved.id().value(), "sequence", sequence,
                                "positionSeconds", positionSeconds, "completed", completed)))
                        .thenReturn(saved));
              });
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
