package com.gcorp.service.app.mvflix_activity.feed.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record ProjectActivityEvent(
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
    JsonNode payload) {
  public ProjectActivityEvent {
    if (eventId == null || eventType == null || eventType.isBlank() || eventVersion != 1
        || occurredAt == null || actorId == null || actorId.isBlank()
        || audienceId == null || audienceId.isBlank() || correlationId == null
        || payload == null || !eventType.startsWith("MediaIngestion")) {
      throw new IllegalArgumentException("Invalid activity event");
    }
  }

  public String status() {
    return switch (eventType) {
      case "MediaIngestionStarted" -> "STARTED";
      case "MediaIngestionCompleted" -> "COMPLETED";
      case "MediaIngestionFailed" -> "FAILED";
      case "MediaIngestionCancelled" -> "CANCELLED";
      default -> throw new IllegalArgumentException("Unsupported activity event: " + eventType);
    };
  }
}
