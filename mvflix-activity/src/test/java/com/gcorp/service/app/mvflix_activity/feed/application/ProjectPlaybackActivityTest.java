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

class ProjectPlaybackActivityTest {
  @Test
  void projectsPlaybackCompletionAndMarksInboxCompleted() {
    var inbox = mock(ActivityFeedInbox.class);
    var projection = mock(ActivityFeedRepository.class);
    var tx = mock(TransactionalOperator.class);
    when(inbox.recordReceived(any(), any())).thenReturn(Mono.empty());
    when(inbox.isCompleted(any())).thenReturn(Mono.just(false));
    when(inbox.markCompleted(any())).thenReturn(Mono.empty());
    when(projection.project(any())).thenReturn(Mono.empty());
    when(tx.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var projector = new ProjectPlaybackActivity(inbox, projection, tx);
    var eventId = UUID.randomUUID();
    var event = new PlaybackActivityCommand(eventId, "PlaybackCompleted", 1, Instant.now(),
        "mvflix-playback", "viewer-1", "viewer-1", UUID.randomUUID(), "PlaybackSession",
        UUID.randomUUID().toString(), 42L, null, "LIBRARY_ASSET", "7", 3600L, 3600L, true, 8L);

    projector.handle(event).block();

    var mutation = (com.gcorp.service.app.mvflix_activity.feed.domain.ActivityMutation)
        captureProjection(projection);
    org.assertj.core.api.Assertions.assertThat(mutation.type()).isEqualTo("PLAYBACK_COMPLETED");
    org.assertj.core.api.Assertions.assertThat(mutation.category()).isEqualTo("PLAYBACK");
    verify(inbox).markCompleted(eventId.toString());
  }

  private static Object captureProjection(ActivityFeedRepository projection) {
    var captor = org.mockito.ArgumentCaptor.forClass(
        com.gcorp.service.app.mvflix_activity.feed.domain.ActivityMutation.class);
    verify(projection).project(captor.capture());
    return captor.getValue();
  }
}
