package com.gcorp.service.app.mvflix_playback.domain;

public record PlaybackPosition(long seconds, Long durationSeconds) {
  private static final long DURATION_TOLERANCE_SECONDS = 30;

  public PlaybackPosition(long seconds, long durationSeconds) {
    this(seconds, Long.valueOf(durationSeconds));
  }

  public PlaybackPosition {
    if (seconds < 0) {
      throw new IllegalArgumentException("Playback position cannot be negative");
    }
    if (durationSeconds != null && durationSeconds <= 0) {
      throw new IllegalArgumentException("Playback duration must be positive");
    }
    if (durationSeconds != null && seconds > durationSeconds + DURATION_TOLERANCE_SECONDS) {
      throw new IllegalArgumentException("Playback position exceeds duration");
    }
  }
}
