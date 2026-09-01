package com.gcorp.service.app.mvflix_activity.feed.application;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedInbox;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityProjection;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

class ProjectActivityEventTest {
  @Test
  void completedEventIsNotProjectedAgain() {
    var inbox = mock(ActivityFeedInbox.class);
    var projection = mock(ActivityProjection.class);
    var tx = mock(TransactionalOperator.class);
    when(inbox.recordReceived(any(), any())).thenReturn(Mono.empty());
    when(inbox.isCompleted(any())).thenReturn(Mono.just(true));
    when(tx.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var projector = new ProjectActivityEvent(inbox, projection, tx);
    var event = new com.gcorp.service.app.mvflix_activity.feed.domain.ProjectActivityEvent(
        UUID.randomUUID(), "MediaIngestionCompleted", 1, Instant.now(), "producer", "actor",
        "audience", UUID.randomUUID(), "MediaIngestion", "id", JsonNodeFactory.instance.objectNode());

    projector.handle(event).block();

    verify(projection, never()).project(event);
    verify(inbox, never()).markCompleted(any());
  }
}
