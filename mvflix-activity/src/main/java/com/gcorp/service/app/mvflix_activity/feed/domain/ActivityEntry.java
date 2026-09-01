package com.gcorp.service.app.mvflix_activity.feed.domain;

import java.time.Instant;
import java.util.UUID;

public record ActivityEntry(
    UUID activityId,
    UUID correlationId,
    String type,
    String status,
    Instant startedAt,
    Instant lastOccurredAt,
    String fileName,
    Long catalogItemId,
    String failureCode,
    String cursor) {}
