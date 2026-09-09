package com.gcorp.service.app.mvflix_activity.feed.application;

import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedInbox;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedRepository;
import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityMutation;
import java.util.Map;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

public class ProjectPlaybackActivity {
  private final ActivityFeedInbox inbox;
  private final ActivityFeedRepository projection;
  private final TransactionalOperator tx;

  public ProjectPlaybackActivity(ActivityFeedInbox inbox, ActivityFeedRepository projection,
      TransactionalOperator tx) {
    this.inbox = inbox;
    this.projection = projection;
    this.tx = tx;
  }

  public Mono<Void> handle(PlaybackActivityCommand event) {
    String id = event.eventId().toString();
    String type = "PLAYBACK";
    String status = "PlaybackCompleted".equals(event.eventType()) ? "COMPLETED" : "STARTED";
    return tx.transactional(inbox.recordReceived(id, event.eventType())
        .then(inbox.isCompleted(id).flatMap(done -> done ? Mono.empty()
            : projection.project(new ActivityMutation(event.eventId(), event.audienceId(), event.actorId(),
                event.correlationId(), type, status, event.occurredAt(), event.occurredAt(),
                event.eventId(), event.eventType(), null, event.movieId(), null,
                "playback:" + event.aggregateId(), "PLAYBACK", "INFO", "CatalogItem",
                event.movieId() == null ? null : event.movieId().toString(), null,
                context(event)))
                .then(inbox.markCompleted(id)))))
        .onErrorResume(error -> inbox.markFailed(id, event.eventType(), error.toString())
            .then(Mono.error(error)));
  }

  private static Map<String, Object> context(PlaybackActivityCommand event) {
    var context = new java.util.HashMap<String, Object>();
    if (event.mediaId() != null) context.put("mediaId", event.mediaId());
    if (event.contentReferenceType() != null) context.put("contentReferenceType", event.contentReferenceType());
    if (event.positionSeconds() != null) context.put("positionSeconds", event.positionSeconds());
    if (event.durationSeconds() != null) context.put("durationSeconds", event.durationSeconds());
    return context;
  }
}
