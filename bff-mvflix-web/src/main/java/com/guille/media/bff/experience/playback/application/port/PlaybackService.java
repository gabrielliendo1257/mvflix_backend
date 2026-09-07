package com.guille.media.bff.experience.playback.application.port;

import com.guille.media.bff.experience.playback.application.DirectSource;
import reactor.core.publisher.Mono;

public interface PlaybackService {
  Mono<StartedSession> start(long mediaId);

  Mono<ProgressResult> progress(String sessionId, ProgressCommand command);

  record StartedSession(String sessionId, DirectSource source, Long resumePositionSeconds) {}

  record ProgressCommand(long sequence, long positionSeconds, Long durationSeconds,
      boolean completed) {}

  record ProgressResult(long sequence, Long positionSeconds, String status) {}
}
