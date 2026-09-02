package com.gcorp.service.app.mvflix_activity.feed.application;

import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedInbox;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedRepository;
import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityMutation;
import java.util.Map;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

public class ProjectCatalogItemAccessChanged {
  private final ActivityFeedInbox inbox;
  private final ActivityFeedRepository projection;
  private final TransactionalOperator tx;

  public ProjectCatalogItemAccessChanged(ActivityFeedInbox inbox, ActivityFeedRepository projection,
      TransactionalOperator tx) {
    this.inbox = inbox;
    this.projection = projection;
    this.tx = tx;
  }

  public Mono<Void> handle(CatalogItemAccessChangedCommand event) {
    String id = event.eventId().toString();
    return tx.transactional(inbox.recordReceived(id, event.eventType())
        .then(inbox.isCompleted(id)
            .flatMap(done -> done ? Mono.empty()
                : projection.project(new ActivityMutation(event.eventId(), event.audienceId(), event.actorId(),
                    event.correlationId(), "CATALOG_ACCESS", "ACCESS_CHANGED", event.occurredAt(),
                    event.occurredAt(), event.eventId(), event.eventType(), null, event.catalogItemId(), null,
                    "event:" + event.eventId(), "CATALOG", "INFO", "CatalogItem", event.aggregateId(),
                    event.title(), Map.of("previousVisibility", event.previousVisibility(),
                        "visibility", event.visibility(), "previousSharedCount", event.previousSharedCount(),
                        "sharedCount", event.sharedCount()))).then(inbox.markCompleted(id)))))
        .onErrorResume(error -> inbox.markFailed(id, event.eventType(), error.toString())
            .then(Mono.error(error)));
  }
}
