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

  @Test
  void parsesCatalogItemAccessChangedContract() {
    UUID eventId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    var event = new CatalogItemAccessChangedParser(new ObjectMapper()).parse("""
        {"eventId":"%s","eventType":"CatalogItemAccessChanged","eventVersion":1,
         "occurredAt":"2026-09-01T16:00:00Z","producer":"mvflix-movies",
         "actorId":"user-123","audienceId":"user-123","correlationId":"%s",
         "aggregate":{"type":"CatalogItem","id":"42"},
         "payload":{"catalogItemId":42,"kind":"MOVIE","title":"Interstellar",
          "previousVisibility":"PRIVATE","visibility":"SHARED",
          "previousSharedCount":0,"sharedCount":3}}
        """.formatted(eventId, correlationId));

    assertThat(event.eventType()).isEqualTo("CatalogItemAccessChanged");
    assertThat(event.eventVersion()).isEqualTo(1);
    assertThat(event.audienceId()).isEqualTo("user-123");
    assertThat(event.aggregateId()).isEqualTo("42");
    assertThat(event.status()).isEqualTo("ACCESS_CHANGED");
    assertThat(event.sharedCount()).isEqualTo(3);
  }

  @Test
  void rejectsCatalogAccessFromUnexpectedProducer() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> new CatalogItemAccessChangedParser(new ObjectMapper()).parse("""
        {"eventId":"%s","eventType":"CatalogItemAccessChanged","eventVersion":1,
         "occurredAt":"2026-09-01T16:00:00Z","producer":"other",
         "actorId":"user-123","audienceId":"user-123","correlationId":"%s",
         "aggregate":{"type":"CatalogItem","id":"42"},
         "payload":{"catalogItemId":42,"kind":"MOVIE","title":"Interstellar",
          "previousVisibility":"PRIVATE","visibility":"SHARED",
          "previousSharedCount":0,"sharedCount":3}}
        """.formatted(id, id))).isInstanceOf(IllegalArgumentException.class);
  }
}
