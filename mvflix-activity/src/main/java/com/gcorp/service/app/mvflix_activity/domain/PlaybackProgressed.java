package com.gcorp.service.app.mvflix_activity.domain;

import java.util.UUID;

public record PlaybackProgressed(String eventId, String eventType, int eventVersion, String producer,
    String aggregateType, String aggregateId, String ownerUsername, long movieId, Long mediaId,
    long positionSeconds, Long durationSeconds, boolean completed, long sequence) {
  public PlaybackProgressed {
    if (eventId == null || eventId.isBlank() || invalidUuid(eventId)
        || !("PlaybackProgressed".equals(eventType) || "PlaybackCompleted".equals(eventType))
        || eventVersion != 1
        || producer == null || producer.isBlank() || !"PlaybackSession".equals(aggregateType)
        || aggregateId == null || aggregateId.isBlank() || ownerUsername == null || ownerUsername.isBlank() || movieId <= 0
        || (mediaId != null && mediaId <= 0) || positionSeconds < 0 || sequence <= 0
        || (durationSeconds != null && durationSeconds < 0)) throw new IllegalArgumentException("Invalid PlaybackProgressed v1");
  }
  private static boolean invalidUuid(String value) { try { UUID.fromString(value); return false; } catch (IllegalArgumentException e) { return true; } }
}
