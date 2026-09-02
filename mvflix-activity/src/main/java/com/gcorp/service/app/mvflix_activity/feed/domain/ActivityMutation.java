package com.gcorp.service.app.mvflix_activity.feed.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ActivityMutation(
    UUID activityId,
    String audienceId,
    String actorId,
    UUID correlationId,
    String type,
    String status,
    Instant startedAt,
    Instant occurredAt,
    UUID eventId,
    String eventType,
    String fileName,
    Long catalogItemId,
    String failureCode,
    String activityKey,
    String category,
    String severity,
    String resourceType,
    String resourceId,
    String resourceTitle,
    Map<String, Object> context) {}
