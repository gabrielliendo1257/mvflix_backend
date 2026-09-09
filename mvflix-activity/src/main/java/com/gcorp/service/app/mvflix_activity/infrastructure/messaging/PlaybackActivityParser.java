package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcorp.service.app.mvflix_activity.feed.application.PlaybackActivityCommand;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PlaybackActivityParser {
  private final ObjectMapper mapper;

  public PlaybackActivityParser(ObjectMapper mapper) { this.mapper = mapper; }

  public PlaybackActivityCommand parse(String json) {
    try {
      JsonNode n = mapper.readTree(json), a = n.path("aggregate"), p = n.path("payload");
      String type = n.path("eventType").asText();
      if (!n.has("eventId") || !a.has("id") || !p.isObject()
          || !("PlaybackStarted".equals(type) || "PlaybackCompleted".equals(type))) {
        throw new IllegalArgumentException("Invalid playback activity event");
      }
      return new PlaybackActivityCommand(UUID.fromString(n.path("eventId").asText()), type,
          n.path("eventVersion").asInt(), Instant.parse(n.path("occurredAt").asText()),
          n.path("producer").asText(), p.path("ownerUsername").asText(),
          n.path("audienceId").asText(p.path("ownerUsername").asText()),
          UUID.fromString(n.path("correlationId").asText()), a.path("type").asText(),
          a.path("id").asText(), p.path("movieId").isMissingNode() ? null : p.path("movieId").asLong(),
          p.path("mediaId").isNull() || p.path("mediaId").isMissingNode() ? null : p.path("mediaId").asLong(),
          p.path("contentReferenceType").asText(null), p.path("contentReferenceId").asText(null),
          p.path("positionSeconds").isMissingNode() ? null : p.path("positionSeconds").asLong(),
          p.path("durationSeconds").isNull() || p.path("durationSeconds").isMissingNode() ? null : p.path("durationSeconds").asLong(),
          p.path("completed").asBoolean(false), p.path("sequence").isMissingNode() ? null : p.path("sequence").asLong());
    } catch (IllegalArgumentException error) { throw error; }
    catch (Exception error) { throw new IllegalArgumentException("Invalid playback activity JSON", error); }
  }
}
