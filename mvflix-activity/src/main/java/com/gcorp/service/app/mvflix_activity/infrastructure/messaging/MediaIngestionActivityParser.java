package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MediaIngestionActivityParser {
  private final ObjectMapper mapper;

  public MediaIngestionActivityParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public com.gcorp.service.app.mvflix_activity.feed.domain.ProjectActivityEvent parse(String json) {
    try {
      JsonNode n = mapper.readTree(json);
      JsonNode aggregate = n.path("aggregate");
      JsonNode payload = n.path("payload");
      return new com.gcorp.service.app.mvflix_activity.feed.domain.ProjectActivityEvent(
          UUID.fromString(n.path("eventId").asText()), n.path("eventType").asText(),
          n.path("eventVersion").asInt(), Instant.parse(n.path("occurredAt").asText()),
          n.path("producer").asText(), n.path("actorId").asText(), n.path("audienceId").asText(),
          UUID.fromString(n.path("correlationId").asText()), aggregate.path("type").asText(),
          aggregate.path("id").asText(), payload);
    } catch (Exception error) {
      throw new IllegalArgumentException("Invalid media ingestion activity event", error);
    }
  }
}
