package com.gcorp.service.app.mvflix_activity.feed.application;

import java.time.Instant;
import java.util.UUID;

public record PlaybackActivityCommand(
    UUID eventId, String eventType, int eventVersion, Instant occurredAt, String producer,
    String actorId, String audienceId, UUID correlationId, String aggregateType, String aggregateId,
    Long movieId, Long mediaId, String contentReferenceType, String contentReferenceId,
    Long positionSeconds, Long durationSeconds, boolean completed, Long sequence) {
  public PlaybackActivityCommand {
    if (eventId == null || eventType == null
        || !(eventType.equals("PlaybackStarted") || eventType.equals("PlaybackCompleted"))
        || eventVersion != 1 || occurredAt == null || !"mvflix-playback".equals(producer)
        || actorId == null || actorId.isBlank() || audienceId == null || audienceId.isBlank()
        || correlationId == null || !"PlaybackSession".equals(aggregateType)
        || aggregateId == null || aggregateId.isBlank() || movieId == null || movieId < 1
        || (mediaId != null && mediaId < 1) || (positionSeconds != null && positionSeconds < 0)
        || (durationSeconds != null && durationSeconds < 1)
        || (sequence != null && sequence < 1)) {
      throw new IllegalArgumentException("Invalid playback activity event");
    }
  }
}
