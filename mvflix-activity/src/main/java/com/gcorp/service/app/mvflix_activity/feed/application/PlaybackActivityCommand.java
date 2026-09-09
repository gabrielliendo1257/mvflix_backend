package com.gcorp.service.app.mvflix_activity.feed.application;

import java.time.Instant;
import java.util.UUID;

public record PlaybackActivityCommand(
    UUID eventId, String eventType, int eventVersion, Instant occurredAt, String producer,
    String actorId, String audienceId, UUID correlationId, String aggregateType, String aggregateId,
    Long movieId, Long mediaId, String contentReferenceType, String contentReferenceId,
    Long positionSeconds, Long durationSeconds, boolean completed, Long sequence) {}
