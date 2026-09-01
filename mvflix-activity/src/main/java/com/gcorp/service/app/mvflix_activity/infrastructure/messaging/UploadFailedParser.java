package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcorp.service.app.mvflix_activity.feed.application.UploadFailedCommand;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UploadFailedParser {
  private final ObjectMapper mapper;

  public UploadFailedParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public UploadFailedCommand parse(String json) {
    try {
      JsonNode node = mapper.readTree(json);
      JsonNode aggregate = node.path("aggregate");
      JsonNode payload = node.path("payload");
      return new UploadFailedCommand(UUID.fromString(node.path("eventId").asText()),
          node.path("eventType").asText(), node.path("eventVersion").asInt(),
          Instant.parse(node.path("occurredAt").asText()), node.path("producer").asText(),
          node.path("actorId").asText(), node.path("audienceId").asText(),
          UUID.fromString(node.path("correlationId").asText()), aggregate.path("type").asText(),
          aggregate.path("id").asText(), payload.path("storageId").asLong(),
          payload.path("ownerUsername").asText(), payload.path("objectKey").asText(),
          payload.path("reason").asText());
    } catch (Exception error) {
      throw new IllegalArgumentException("Invalid upload failed event", error);
    }
  }
}
