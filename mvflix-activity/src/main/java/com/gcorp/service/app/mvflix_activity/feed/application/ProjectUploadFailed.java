package com.gcorp.service.app.mvflix_activity.feed.application;

import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedInbox;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedRepository;
import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityMutation;
import java.util.Map;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

public class ProjectUploadFailed {
  private final ActivityFeedInbox inbox;
  private final ActivityFeedRepository projection;
  private final TransactionalOperator tx;

  public ProjectUploadFailed(ActivityFeedInbox inbox, ActivityFeedRepository projection,
      TransactionalOperator tx) {
    this.inbox = inbox;
    this.projection = projection;
    this.tx = tx;
  }

  public Mono<Void> handle(UploadFailedCommand event) {
    String id = event.eventId().toString();
    return tx.transactional(inbox.recordReceived(id, event.eventType())
        .then(inbox.isCompleted(id)
            .flatMap(done -> done ? Mono.empty()
                : projection.project(new ActivityMutation(event.eventId(), event.audienceId(), event.actorId(),
                    event.correlationId(), "UPLOAD_FAILED", "FAILED", event.occurredAt(), event.occurredAt(),
                    event.eventId(), event.eventType(), null, null, null, "event:" + event.eventId(),
                    "STORAGE", "ERROR", "ManagedObject", event.aggregateId(), event.objectKey(),
                    Map.of("reason", event.reason()))).then(inbox.markCompleted(id)))))
        .onErrorResume(error -> inbox.markFailed(id, event.eventType(), error.toString())
            .then(Mono.error(error)));
  }
}
