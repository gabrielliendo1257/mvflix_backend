package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcorp.service.app.mvflix_activity.domain.PlaybackProgressed;
import org.springframework.stereotype.Component;

@Component
public class PlaybackProgressedParser {
  private final ObjectMapper mapper;
  public PlaybackProgressedParser(ObjectMapper mapper) { this.mapper = mapper; }
  public PlaybackProgressed parse(String json) {
    try {
      JsonNode n = mapper.readTree(json), a = n.path("aggregate"), p = n.path("payload");
      if (n.path("eventId").isMissingNode() || a.isMissingNode() || p.isMissingNode()) throw new IllegalArgumentException("Missing event envelope fields");
      String viewerId = p.has("viewerId") ? p.path("viewerId").asText() : p.path("ownerUsername").asText();
      long catalogItemId = p.has("catalogItemId") ? p.path("catalogItemId").asLong(-1) : p.path("movieId").asLong(-1);
      return new PlaybackProgressed(n.path("eventId").asText(), n.path("eventType").asText(), n.path("eventVersion").asInt(),
          n.path("producer").asText(), a.path("type").asText(), a.path("id").asText(), viewerId,
          catalogItemId, p.path("mediaId").isNull() || p.path("mediaId").isMissingNode() ? null : p.path("mediaId").asLong(),
          p.path("positionSeconds").asLong(-1), p.path("durationSeconds").isNull() || p.path("durationSeconds").isMissingNode() ? null : p.path("durationSeconds").asLong(),
          p.path("completed").asBoolean(false), p.path("sequence").asLong(-1));
    } catch (IllegalArgumentException e) { throw e; } catch (Exception e) { throw new IllegalArgumentException("Invalid PlaybackProgressed JSON", e); }
  }
}
