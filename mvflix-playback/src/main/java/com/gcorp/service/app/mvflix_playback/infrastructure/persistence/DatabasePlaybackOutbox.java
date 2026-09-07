package com.gcorp.service.app.mvflix_playback.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcorp.service.app.mvflix_playback.application.PlaybackOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class DatabasePlaybackOutbox implements PlaybackOutbox {
  private final DatabaseClient database;
  private final ObjectMapper mapper;

  public DatabasePlaybackOutbox(DatabaseClient database, ObjectMapper mapper) {
    this.database = database;
    this.mapper = mapper;
  }

  @Override
  public Mono<Void> append(String type, UUID aggregateId, Object payload) {
    try {
      var eventId = UUID.randomUUID();
      var envelope = Map.of("eventId", eventId, "eventType", type, "eventVersion", 1,
          "occurredAt", Instant.now(), "producer", "mvflix-playback", "aggregateId", aggregateId,
          "payload", payload);
      return database.sql("INSERT INTO playback_outbox(event_id,event_type,aggregate_id,occurred_at,payload) VALUES(:id,:type,:aggregate,:occurred,CAST(:payload AS jsonb))")
          .bind("id", eventId).bind("type", type).bind("aggregate", aggregateId)
          .bind("occurred", envelope.get("occurredAt")).bind("payload", mapper.writeValueAsString(envelope))
          .fetch().rowsUpdated().then();
    } catch (Exception error) {
      return Mono.error(error);
    }
  }

  @Override
  public Flux<Message> claim(int limit, int maxAttempts, Duration lease) {
    return database.sql("""
        WITH candidates AS (SELECT event_id FROM playback_outbox WHERE published_at IS NULL
          AND attempts < :maxAttempts AND next_attempt_at <= now()
          AND (claimed_until IS NULL OR claimed_until < now()) ORDER BY occurred_at LIMIT :limit
          FOR UPDATE SKIP LOCKED)
        UPDATE playback_outbox e SET attempts=e.attempts+1,
          claimed_until=now()+make_interval(secs => :lease) FROM candidates c WHERE e.event_id=c.event_id
          RETURNING e.event_id,e.event_type,e.aggregate_id,e.payload::text,e.occurred_at
        """).bind("limit", limit).bind("maxAttempts", maxAttempts)
        .bind("lease", Math.max(1, lease.toSeconds()))
        .map((row, metadata) -> new Message(row.get("event_id", UUID.class),
            row.get("event_type", String.class), row.get("aggregate_id", UUID.class),
            row.get("payload", String.class), row.get("occurred_at", Instant.class))).all();
  }

  public Mono<Void> markPublished(UUID id) {
    return database.sql("UPDATE playback_outbox SET published_at=now(),claimed_until=NULL WHERE event_id=:id")
        .bind("id", id).fetch().rowsUpdated().then();
  }

  public Mono<Void> markFailed(UUID id, String error, Duration delay) {
    return database.sql("UPDATE playback_outbox SET claimed_until=NULL,next_attempt_at=now()+make_interval(secs => :delay),last_error=:error WHERE event_id=:id")
        .bind("id", id).bind("delay", Math.max(1, delay.toSeconds()))
        .bind("error", error == null ? "unknown" : error.substring(0, Math.min(2000, error.length())))
        .fetch().rowsUpdated().then();
  }
}
