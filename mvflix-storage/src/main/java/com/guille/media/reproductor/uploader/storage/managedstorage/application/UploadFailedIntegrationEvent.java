package com.guille.media.reproductor.uploader.storage.managedstorage.application;

import java.time.Instant;
import java.util.UUID;

public record UploadFailedIntegrationEvent(
    UUID eventId,
    int eventVersion,
    Instant occurredAt,
    String actorId,
    String audienceId,
    UUID correlationId,
    String aggregateId,
    UploadFailedPayload payload)
    implements StorageIntegrationEvent<UploadFailedIntegrationEvent.UploadFailedPayload> {
  @Override
  public String eventType() {
    return "UploadFailed";
  }

  @Override
  public String aggregateType() {
    return "ManagedObject";
  }

  @Override
  public String audienceId() {
    return this.audienceId;
  }

  public record UploadFailedPayload(
      Long storageId, String ownerUsername, String objectKey, String reason) {}
}
