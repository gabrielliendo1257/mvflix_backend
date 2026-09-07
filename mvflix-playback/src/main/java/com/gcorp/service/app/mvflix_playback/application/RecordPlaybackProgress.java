package com.gcorp.service.app.mvflix_playback.application;

import com.gcorp.service.app.mvflix_playback.application.port.PlaybackSessionRepository;
import com.gcorp.service.app.mvflix_playback.application.port.WatchProgressRepository;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackPosition;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import com.gcorp.service.app.mvflix_playback.domain.ContentReference;
import com.gcorp.service.app.mvflix_playback.domain.LibraryAssetReference;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
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
                  return saveSession(session, sequence).map(SessionSave::session);
                }
                var payload = new HashMap<String, Object>();
                payload.put("ownerUsername", viewerId.value());
                payload.put("movieId", session.catalogItemId().value());
                payload.put("mediaId", mediaId(session.contentReference()));
                payload.put("contentReferenceType", session.contentReference().type());
                payload.put("contentReferenceId", session.contentReference().value());
                payload.put("sessionId", session.id().value());
                payload.put("sequence", sequence);
                payload.put("positionSeconds", positionSeconds);
                payload.put("durationSeconds", durationSeconds);
                payload.put("completed", completed);
                var metadata = new PlaybackOutbox.EventMetadata(viewerId.value(), viewerId.value(),
                    UUID.randomUUID(), null);
                return saveSession(session, sequence)
                    .flatMap(saved -> saved.persisted()
                        ? progress.save(watch)
                            .then(Mono.defer(() -> outbox.append(
                                completed ? "PlaybackCompleted" : "PlaybackProgressed",
                                saved.session().id().value(), payload, metadata)))
                            .onErrorResume(OptimisticLockingFailureException.class,
                                error -> Mono.empty())
                            .thenReturn(saved.session())
                        : Mono.just(saved.session()));
              });
         });
  }

  private Mono<SessionSave> saveSession(PlaybackSession session, long sequence) {
    return sessions.save(session)
        .map(saved -> new SessionSave(saved, true))
        .onErrorResume(OptimisticLockingFailureException.class, error ->
            sessions.findById(session.id())
                .switchIfEmpty(Mono.error(error))
                .flatMap(current -> current.lastSequence() >= sequence
                    ? Mono.just(new SessionSave(current, false)) : Mono.error(error)));
  }

  private record SessionSave(PlaybackSession session, boolean persisted) {}

  private static Long mediaId(ContentReference reference) {
    return reference instanceof LibraryAssetReference local ? local.assetId() : null;
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
