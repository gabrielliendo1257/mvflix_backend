package com.gcorp.service.app.mvflix_playback.application;

import com.gcorp.service.app.mvflix_playback.application.port.PlaybackSessionRepository;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
public class PersistPlaybackSession {
  private final PlaybackSessionRepository sessions;
  private final PlaybackOutbox outbox;

  public PersistPlaybackSession(PlaybackSessionRepository sessions, PlaybackOutbox outbox) {
    this.sessions = sessions;
    this.outbox = outbox;
  }

  @Transactional("connectionFactoryTransactionManager")
  public Mono<PlaybackSession> execute(PlaybackSession session, Map<String, Object> payload,
      PlaybackOutbox.EventMetadata metadata) {
    return sessions.save(session)
        .flatMap(saved -> outbox.append("PlaybackStarted", saved.id().value(), payload, metadata)
            .thenReturn(saved));
  }
}
