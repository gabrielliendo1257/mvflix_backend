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
    String type = normalizedType(entry);
    String title = title(type, entry);
    String description = description(type, entry);
    Resource resource = entry.resourceType() == null ? null : new Resource(entry.resourceType(),
        entry.resourceId(), entry.resourceTitle(), null);
    var actions = actionsFor(type, resource);
    return new ActivityEntry(entry.activityId(), type, category(type, entry.category()),
        entry.severity(), title, description,
        entry.lastOccurredAt() == null ? entry.startedAt() : entry.lastOccurredAt(), resource,
        entry.context() == null ? Map.of() : entry.context(), actions, entry.cursor());
  }

  private static String normalizedType(DownstreamEntry entry) {
    return switch (entry.type() == null ? "" : entry.type()) {
      case "PLAYBACK" -> "COMPLETED".equals(entry.status())
          ? "PLAYBACK_COMPLETED" : "PLAYBACK_STARTED";
      case "MEDIA_INGESTION" -> "MEDIA_INGESTION_" + entry.status();
      default -> entry.type() == null ? "ACTIVITY" : entry.type().toUpperCase().replace('-', '_');
    };
  }

  private static String category(String type, String category) {
    if (type.startsWith("PLAYBACK_")) return "PLAYBACK";
    if (type.equals("UPLOAD_FAILED")) return "STORAGE";
    return firstNonBlank(category, null, "SYSTEM");
  }

  private static String title(String type, DownstreamEntry entry) {
    return switch (type) {
      case "UPLOAD_FAILED", "MEDIA_INGESTION_FAILED" ->
          firstNonBlank(entry.resourceTitle(), entry.fileName(), "Upload") + " upload failed";
      case "CATALOG_ACCESS_CHANGED" -> "Visibility changed";
      case "PLAYBACK_COMPLETED" -> "Playback completed";
      case "PLAYBACK_STARTED" -> "Playback started";
      case "MEDIA_INGESTION_COMPLETED" -> "Upload completed";
      default -> firstNonBlank(entry.resourceTitle(), entry.fileName(), "Activity");
    };
  }

  private static String description(String type, DownstreamEntry entry) {
    if (type.equals("CATALOG_ACCESS_CHANGED")) {
      String previous = value(entry.context(), "previousVisibility");
       String current = value(entry.context(), "visibility");
      return previous == null || current == null ? "Visibility changed"
          : firstNonBlank(entry.resourceTitle(), "Media", "Media")
              + " changed from " + previous + " to " + current;
    }
    if (type.equals("UPLOAD_FAILED") || type.equals("MEDIA_INGESTION_FAILED")) {
      return firstNonBlank(value(entry.context(), "reason"),
          entry.failureCode() == null ? null : humanize(entry.failureCode()), "Upload failed");
    }
    return switch (type) {
      case "PLAYBACK_COMPLETED" -> "Playback completed";
      case "PLAYBACK_STARTED" -> "Playback started";
      default -> firstNonBlank(entry.failureCode(), entry.status(), "Activity updated");
    };
  }

  private static List<Action> actionsFor(String type, Resource resource) {
    if ((type.equals("CATALOG_ACCESS_CHANGED") || type.startsWith("PLAYBACK_"))
        && resource != null && resource.id() != null
        && ("CatalogItem".equalsIgnoreCase(resource.type()) || "MEDIA".equalsIgnoreCase(resource.type()))) {
      return List.of(new Action("VIEW_DETAILS", "View details", "/media/" + resource.id()));
    }
    return Collections.emptyList();
  }

  private static String value(Map<String, Object> context, String key) {
    Object value = context == null ? null : context.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static String firstNonBlank(String first, String second, String fallback) {
    return first != null && !first.isBlank() ? first
        : second != null && !second.isBlank() ? second : fallback;
  }

  private static String humanize(String value) {
    return value.toLowerCase().replace('_', ' ');
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
