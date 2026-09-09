package com.guille.media.bff.experience.playback.application.port;

import com.guille.media.bff.experience.playback.application.DirectSource;
import reactor.core.publisher.Mono;

public interface PlaybackService {
  Mono<StartedSession> start(long mediaId);

  default Mono<StartedSession> start(long mediaId, String viewerId) {
    return start(mediaId);
  }

  Mono<ProgressResult> progress(String sessionId, ProgressCommand command);

  default Mono<ProgressResult> progress(String sessionId, String viewerId, ProgressCommand command) {
    return progress(sessionId, command);
  }

  record StartedSession(String sessionId, DirectSource source, Long resumePositionSeconds) {}

  record ProgressCommand(long sequence, long positionSeconds, Long durationSeconds,
      boolean completed) {}

  record ProgressResult(long sequence, Long positionSeconds, String status) {}
}
