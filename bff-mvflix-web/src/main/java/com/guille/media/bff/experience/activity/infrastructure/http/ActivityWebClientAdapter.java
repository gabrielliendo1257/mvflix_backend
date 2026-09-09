package com.guille.media.bff.experience.activity.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.guille.media.bff.experience.activity.application.GetActivityFeed.ActivityEntry;
import com.guille.media.bff.experience.activity.application.GetActivityFeed.ActivityPage;
import com.guille.media.bff.experience.activity.application.GetActivityFeed.Action;
import com.guille.media.bff.experience.activity.application.GetActivityFeed.Resource;
import com.guille.media.bff.experience.activity.application.port.ActivityProjection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
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
          String nextCursor = hasMore && !items.isEmpty()
              ? items.get(items.size() - 1).cursor() : null;
          return new ActivityPage(items, nextCursor, hasMore);
        });
  }

  static ActivityEntry toApplication(DownstreamEntry entry) {
    String title = firstNonBlank(entry.resourceTitle(), entry.fileName(), "Activity");
    String description = entry.failureCode() == null
        ? firstNonBlank(entry.status(), null, "Activity updated")
        : humanize(entry.failureCode());
    Resource resource = entry.resourceType() == null ? null : new Resource(entry.resourceType(),
        entry.resourceId(), entry.resourceTitle(), mediaThumbnail(entry.resourceId()));
    var actions = resource == null || resource.id() == null ? Collections.<Action>emptyList()
        : List.of(new Action("VIEW_DETAILS", "View details", "/media/" + resource.id()));
    return new ActivityEntry(entry.activityId(), normalizedType(entry.type()), entry.category(),
        entry.severity(), title, description,
        entry.lastOccurredAt() == null ? entry.startedAt() : entry.lastOccurredAt(), resource,
        entry.context() == null ? Map.of() : entry.context(), actions, entry.cursor());
  }

  private static String normalizedType(String type) {
    return type == null ? "ACTIVITY" : type.toUpperCase().replace('-', '_');
  }

  private static String firstNonBlank(String first, String second, String fallback) {
    return first != null && !first.isBlank() ? first
        : second != null && !second.isBlank() ? second : fallback;
  }

  private static String humanize(String value) {
    return value.toLowerCase().replace('_', ' ');
  }

  private static String mediaThumbnail(String resourceId) {
    return resourceId == null ? null : "/web/media/" + resourceId + "/poster";
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
      String cursor,
      String category,
      String severity,
      String resourceType,
      String resourceId,
      String resourceTitle,
       Map<String, Object> context) {}
}
