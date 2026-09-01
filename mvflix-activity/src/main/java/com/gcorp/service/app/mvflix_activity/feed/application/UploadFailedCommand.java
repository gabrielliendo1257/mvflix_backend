package com.gcorp.service.app.mvflix_activity.feed.application;

import java.time.Instant;
import java.util.UUID;

public record UploadFailedCommand(
    UUID eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String producer,
    String actorId,
    String audienceId,
    UUID correlationId,
    String aggregateType,
    String aggregateId,
    Long storageId,
    String ownerUsername,
    String objectKey,
    String reason) {
  public UploadFailedCommand {
    if (eventId == null || !"UploadFailed".equals(eventType) || eventVersion != 1
        || occurredAt == null || !"mvflix-storage".equals(producer)
        || actorId == null || actorId.isBlank() || audienceId == null || audienceId.isBlank()
        || correlationId == null || !"ManagedObject".equals(aggregateType)
        || aggregateId == null || aggregateId.isBlank() || storageId == null || storageId <= 0
        || !aggregateId.equals(String.valueOf(storageId)) || ownerUsername == null
        || ownerUsername.isBlank() || objectKey == null || objectKey.isBlank()
        || reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Invalid upload failed event");
    }
  }

  public String status() {
    return "FAILED";
  }
}
