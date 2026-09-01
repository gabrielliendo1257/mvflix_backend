package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcorp.service.app.mvflix_activity.feed.application.CatalogItemAccessChangedCommand;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CatalogItemAccessChangedParser {
  private final ObjectMapper mapper;

  public CatalogItemAccessChangedParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public CatalogItemAccessChangedCommand parse(String json) {
    try {
      JsonNode node = mapper.readTree(json);
      JsonNode aggregate = node.path("aggregate");
      JsonNode payload = node.path("payload");
      return new CatalogItemAccessChangedCommand(
          UUID.fromString(node.path("eventId").asText()),
          node.path("eventType").asText(), node.path("eventVersion").asInt(),
          Instant.parse(node.path("occurredAt").asText()), node.path("producer").asText(),
          node.path("actorId").asText(), node.path("audienceId").asText(),
          UUID.fromString(node.path("correlationId").asText()), aggregate.path("type").asText(),
          aggregate.path("id").asText(), payload.path("catalogItemId").asLong(),
          payload.path("kind").asText(), payload.path("title").asText(),
          payload.path("previousVisibility").asText(), payload.path("visibility").asText(),
          payload.path("previousSharedCount").asInt(-1), payload.path("sharedCount").asInt(-1));
    } catch (Exception error) {
      throw new IllegalArgumentException("Invalid catalog item access event", error);
    }
  }
}
