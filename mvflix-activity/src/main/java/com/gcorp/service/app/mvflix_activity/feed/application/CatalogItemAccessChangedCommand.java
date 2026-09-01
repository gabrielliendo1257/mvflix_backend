package com.gcorp.service.app.mvflix_activity.feed.application;

import java.time.Instant;
import java.util.UUID;

public record CatalogItemAccessChangedCommand(
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
    long catalogItemId,
    String kind,
    String title,
    String previousVisibility,
    String visibility,
    int previousSharedCount,
    int sharedCount) {
  public CatalogItemAccessChangedCommand {
    if (eventId == null || occurredAt == null || actorId == null || actorId.isBlank()
        || audienceId == null || audienceId.isBlank() || correlationId == null
        || !"CatalogItemAccessChanged".equals(eventType) || eventVersion != 1
        || !"mvflix-movies".equals(producer) || aggregateId == null || aggregateId.isBlank()
        || !"CatalogItem".equals(aggregateType)
        || catalogItemId <= 0
        || !aggregateId.equals(String.valueOf(catalogItemId))
        || kind == null || kind.isBlank() || title == null || title.isBlank()
        || !validVisibility(previousVisibility) || !validVisibility(visibility)
        || previousSharedCount < 0 || sharedCount < 0) {
      throw new IllegalArgumentException("Invalid catalog item access event");
    }
  }

  public String eventType() {
    return this.eventType;
  }

  public int eventVersion() {
    return this.eventVersion;
  }

  public String aggregateType() {
    return this.aggregateType;
  }

  public String status() {
    return "ACCESS_CHANGED";
  }

  private static boolean validVisibility(String value) {
    return "PRIVATE".equals(value) || "PUBLIC".equals(value) || "SHARED".equals(value);
  }
}
