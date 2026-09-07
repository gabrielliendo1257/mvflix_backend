package com.gcorp.service.app.mvflix_playback.infrastructure.web;

import com.gcorp.service.app.mvflix_playback.application.StartPlayback.PlaybackStarted;
import java.time.Instant;

public record PlaybackResponse(String sessionId, long catalogItemId, String title,
    Long resumePositionSeconds, String strategy, Source source) {
  static PlaybackResponse from(PlaybackStarted started) {
    var source = started.source();
    return new PlaybackResponse(started.session().id().value().toString(),
        started.item().id().value(), started.item().title(), started.resumePositionSeconds(),
        source.strategy(), new Source(source.url(), source.expiresAt(), source.mimeType()));
  }

  record Source(String url, Instant expiresAt, String mimeType) {}
}
