package com.gcorp.service.app.mvflix_playback.domain;

import java.util.UUID;

public record PlaybackSessionId(UUID value) {
  public PlaybackSessionId {
    if (value == null) {
      throw new IllegalArgumentException("Playback session id is required");
    }
  }

  public static PlaybackSessionId generate() {
    return new PlaybackSessionId(UUID.randomUUID());
  }
}
