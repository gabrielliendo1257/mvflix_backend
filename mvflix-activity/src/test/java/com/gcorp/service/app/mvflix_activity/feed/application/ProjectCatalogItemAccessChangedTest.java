package com.gcorp.service.app.mvflix_activity.feed.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedInbox;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

class ProjectCatalogItemAccessChangedTest {
  @Test
  void projectsEventAndMarksInboxCompleted() {
    var inbox = mock(ActivityFeedInbox.class);
    var projection = mock(ActivityFeedRepository.class);
    var tx = mock(TransactionalOperator.class);
    when(inbox.recordReceived(any(), any())).thenReturn(Mono.empty());
    when(inbox.isCompleted(any())).thenReturn(Mono.just(false));
    when(inbox.markCompleted(any())).thenReturn(Mono.empty());
    when(projection.project(any())).thenReturn(Mono.empty());
    when(tx.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var projector = new ProjectCatalogItemAccessChanged(inbox, projection, tx);
    var event = new CatalogItemAccessChangedCommand(UUID.randomUUID(),
        "CatalogItemAccessChanged", 1, Instant.now(), "mvflix-movies", "user-1", "user-1",
        UUID.randomUUID(), "CatalogItem", "42", 42L, "MOVIE", "Interstellar", "PRIVATE",
        "SHARED", 0, 3);

    projector.handle(event).block();

    verify(projection).project(any());
    verify(inbox).markCompleted(event.eventId().toString());
  }
}
