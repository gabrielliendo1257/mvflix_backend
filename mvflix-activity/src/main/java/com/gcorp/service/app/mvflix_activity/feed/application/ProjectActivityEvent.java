package com.gcorp.service.app.mvflix_activity.feed.application;

import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedInbox;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedRepository;
import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityMutation;
import java.util.Map;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

public class ProjectActivityEvent {
  private final ActivityFeedInbox inbox;
  private final ActivityFeedRepository projection;
  private final TransactionalOperator tx;

  public ProjectActivityEvent(ActivityFeedInbox inbox, ActivityFeedRepository projection,
      TransactionalOperator tx) {
    this.inbox = inbox;
    this.projection = projection;
    this.tx = tx;
  }

  public Mono<Void> handle(ProjectActivityCommand event) {
    String id = event.eventId().toString();
    return tx.transactional(inbox.recordReceived(id, event.eventType())
        .then(inbox.isCompleted(id)
            .flatMap(done -> done ? Mono.empty()
                : projection.project(new ActivityMutation(event.correlationId(), event.audienceId(),
                    event.actorId(), event.correlationId(), "MEDIA_INGESTION", event.status(),
                    event.occurredAt(), event.occurredAt(), event.eventId(), event.eventType(),
                    event.fileName(), event.catalogItemId(), event.failureCode(),
                    "ingestion:" + event.correlationId(), "MEDIA",
                    "FAILED".equals(event.status()) ? "ERROR" : "INFO", "MediaIngestion",
                    event.correlationId().toString(), event.fileName(), Map.of()))
                    .then(inbox.markCompleted(id)))))
        .onErrorResume(error -> inbox.markFailed(id, event.eventType(), error.toString())
            .then(Mono.error(error)));
  }
}
