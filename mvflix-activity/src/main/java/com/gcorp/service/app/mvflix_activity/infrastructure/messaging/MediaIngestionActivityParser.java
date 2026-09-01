package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcorp.service.app.mvflix_activity.feed.application.ProjectActivityCommand;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MediaIngestionActivityParser {
  private final ObjectMapper mapper;

  public MediaIngestionActivityParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ProjectActivityCommand parse(String json) {
    try {
      JsonNode n = mapper.readTree(json);
      JsonNode aggregate = n.path("aggregate");
      JsonNode payload = n.path("payload");
      return new ProjectActivityCommand(
          UUID.fromString(n.path("eventId").asText()), n.path("eventType").asText(),
          n.path("eventVersion").asInt(), Instant.parse(n.path("occurredAt").asText()),
          n.path("producer").asText(), n.path("actorId").asText(), n.path("audienceId").asText(),
          UUID.fromString(n.path("correlationId").asText()), aggregate.path("type").asText(),
          aggregate.path("id").asText(), text(payload, "fileName"), number(payload, "catalogItemId"),
          text(payload, "failureCode"));
    } catch (Exception error) {
      throw new IllegalArgumentException("Invalid media ingestion activity event", error);
    }
  }

  private static String text(JsonNode payload, String field) {
    return payload.path(field).isMissingNode() || payload.path(field).isNull()
        ? null : payload.path(field).asText();
  }

  private static Long number(JsonNode payload, String field) {
    return payload.path(field).isMissingNode() || payload.path(field).isNull()
        ? null : payload.path(field).asLong();
  }
}
