package com.guille.media.bff.experience.activity.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.guille.media.bff.experience.activity.application.GetActivityFeed.ActivityEntry;
import com.guille.media.bff.experience.activity.application.GetActivityFeed.ActivityPage;
import com.guille.media.bff.experience.activity.application.port.ActivityProjection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class ActivityWebClientAdapter implements ActivityProjection {
  private final WebClient activityWebClient;

  public ActivityWebClientAdapter(@Qualifier("activityWebClient") WebClient activityWebClient) {
    this.activityWebClient = activityWebClient;
  }

  @Override
  public Mono<ActivityPage> feed(String cursor, int limit) {
    return activityWebClient.get()
        .uri(builder -> {
          builder.path("/api/v1/activity/feed").queryParam("limit", limit + 1);
          if (cursor != null) builder.queryParam("cursor", cursor);
          return builder.build();
        })
        .retrieve()
        .bodyToFlux(DownstreamEntry.class)
        .map(ActivityWebClientAdapter::toApplication)
        .collectList()
        .map(entries -> {
          boolean hasMore = entries.size() > limit;
          List<ActivityEntry> items = hasMore ? entries.subList(0, limit) : entries;
          String nextCursor = items.isEmpty() ? null : items.get(items.size() - 1).cursor();
          return new ActivityPage(items, nextCursor, hasMore);
        });
  }

  static ActivityEntry toApplication(DownstreamEntry entry) {
    return new ActivityEntry(entry.activityId(), entry.correlationId(), entry.type(), entry.status(),
        entry.startedAt(), entry.lastOccurredAt(), entry.fileName(), entry.catalogItemId(),
        entry.failureCode(), entry.cursor());
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DownstreamEntry(
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
}
