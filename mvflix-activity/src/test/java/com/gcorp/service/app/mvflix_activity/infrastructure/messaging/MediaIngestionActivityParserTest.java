package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaIngestionActivityParserTest {
  private final MediaIngestionActivityParser parser = new MediaIngestionActivityParser(new ObjectMapper());

  @Test
  void parsesDurableIdentityAndPayloadContext() {
    UUID eventId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    var event = parser.parse("""
        {"eventId":"%s","eventType":"MediaIngestionCompleted","eventVersion":1,
         "occurredAt":"2026-01-01T12:00:00Z","producer":"mvflix-media-ingestion",
         "actorId":"ana","audienceId":"ana","correlationId":"%s",
         "aggregate":{"type":"MediaIngestion","id":"%s"},
         "payload":{"phase":"COMPLETED","fileName":"movie.mp4","catalogItemId":42}}
        """.formatted(eventId, correlationId, correlationId));

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.correlationId()).isEqualTo(correlationId);
    assertThat(event.audienceId()).isEqualTo("ana");
    assertThat(event.fileName()).isEqualTo("movie.mp4");
    assertThat(event.status()).isEqualTo("COMPLETED");
  }

  @Test
  void rejectsEventsWithoutAudience() {
    assertThatThrownBy(() -> parser.parse("""
        {"eventId":"%s","eventType":"MediaIngestionStarted","eventVersion":1,
         "occurredAt":"2026-01-01T12:00:00Z","producer":"mvflix-media-ingestion",
         "actorId":"ana","correlationId":"%s","aggregate":{},"payload":{}}
        """.formatted(UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
