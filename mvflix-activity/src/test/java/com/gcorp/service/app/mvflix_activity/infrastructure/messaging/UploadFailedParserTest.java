package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadFailedParserTest {
  private final UploadFailedParser parser = new UploadFailedParser(new ObjectMapper());

  @Test
  void parsesUploadFailedContract() {
    UUID eventId = UUID.randomUUID();
    var event = parser.parse("""
        {"eventId":"%s","eventType":"UploadFailed","eventVersion":1,
         "occurredAt":"2026-09-01T16:00:00Z","producer":"mvflix-storage",
         "actorId":"system","audienceId":"user-123","correlationId":"%s",
         "aggregate":{"type":"ManagedObject","id":"7"},
         "payload":{"storageId":7,"ownerUsername":"user-123","objectKey":"movie.mp4",
         "reason":"size mismatch"}}
        """.formatted(eventId, eventId));

    assertThat(event.eventType()).isEqualTo("UploadFailed");
    assertThat(event.status()).isEqualTo("FAILED");
    assertThat(event.storageId()).isEqualTo(7L);
    assertThat(event.audienceId()).isEqualTo("user-123");
    assertThat(event.reason()).isEqualTo("size mismatch");
  }

  @Test
  void rejectsUnexpectedProducer() {
    UUID eventId = UUID.randomUUID();
    assertThatThrownBy(() -> parser.parse("""
        {"eventId":"%s","eventType":"UploadFailed","eventVersion":1,
         "occurredAt":"2026-09-01T16:00:00Z","producer":"other",
         "actorId":"system","audienceId":"user-123","correlationId":"%s",
         "aggregate":{"type":"ManagedObject","id":"7"},
         "payload":{"storageId":7,"ownerUsername":"user-123","objectKey":"movie.mp4",
         "reason":"size mismatch"}}
        """.formatted(eventId, eventId))).isInstanceOf(IllegalArgumentException.class);
  }
}
