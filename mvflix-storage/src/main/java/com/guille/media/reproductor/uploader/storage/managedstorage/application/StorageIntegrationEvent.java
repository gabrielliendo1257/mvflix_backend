package com.guille.media.reproductor.uploader.storage.managedstorage.application;

import java.time.Instant;
import java.util.UUID;

public sealed interface StorageIntegrationEvent<T>
    permits StoredObjectDeletedIntegrationEvent, UploadCompletedIntegrationEvent,
    UploadFailedIntegrationEvent {
  UUID eventId();
  String eventType();
  int eventVersion();
  Instant occurredAt();
  String aggregateId();
  String aggregateType();
  T payload();

  /** Storage no recibe identidad de usuario en los caminos internos; se conserva explícitamente. */
  default String actorId() { return "system"; }
  default String audienceId() { return actorId(); }
  default UUID correlationId() { return eventId(); }
}
