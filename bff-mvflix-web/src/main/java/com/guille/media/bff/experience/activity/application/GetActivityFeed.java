package com.guille.media.bff.experience.activity.application;

import com.guille.media.bff.experience.activity.application.port.ActivityProjection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class GetActivityFeed {
  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 100;

  private final ActivityProjection projection;

  public Mono<ActivityPage> execute(String cursor, Integer limit) {
    int safeLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    return projection.feed(blankToNull(cursor), safeLimit);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

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
      String cursor,
      String category,
      String severity,
      String resourceType,
      String resourceId,
      String resourceTitle,
       Map<String, Object> context) {}

  public record ActivityPage(List<ActivityEntry> items, String nextCursor, boolean hasMore) {}
}
