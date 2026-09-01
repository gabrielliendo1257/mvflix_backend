package com.gcorp.service.app.mvflix_activity.feed.application;

import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedInbox;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityProjection;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

public class ProjectActivityEvent {
  private final ActivityFeedInbox inbox;
  private final ActivityProjection projection;
  private final TransactionalOperator tx;

  public ProjectActivityEvent(ActivityFeedInbox inbox, ActivityProjection projection,
      TransactionalOperator tx) {
    this.inbox = inbox;
    this.projection = projection;
    this.tx = tx;
  }

  public Mono<Void> handle(com.gcorp.service.app.mvflix_activity.feed.domain.ProjectActivityEvent event) {
    String id = event.eventId().toString();
    return inbox.recordReceived(id, event.eventType())
        .then(tx.transactional(inbox.isCompleted(id)
            .flatMap(done -> done ? Mono.empty()
                : projection.project(event).then(inbox.markCompleted(id)))))
        .onErrorResume(error -> inbox.markFailed(id, event.eventType(), error.toString())
            .then(Mono.error(error)));
  }
}
