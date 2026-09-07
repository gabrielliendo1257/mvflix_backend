package com.guille.media.bff.experience.playback.application.port;

import com.guille.media.bff.experience.playback.application.DirectSource;
import reactor.core.publisher.Mono;

public interface PlaybackService {
  Mono<StartedSession> start(long mediaId);

  record StartedSession(String sessionId, DirectSource source, Long resumePositionSeconds) {}
}
