package com.gcorp.service.app.mvflix_playback.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PlaybackOutbox {
  Mono<Void> append(String type, UUID aggregateId, Object payload);
  Flux<Message> claim(int limit, int maxAttempts, Duration lease);
  Mono<Void> markPublished(UUID eventId);
  Mono<Void> markFailed(UUID eventId, String error, Duration delay);
  record Message(UUID eventId, String eventType, UUID aggregateId, String payload, Instant occurredAt) {}
}
