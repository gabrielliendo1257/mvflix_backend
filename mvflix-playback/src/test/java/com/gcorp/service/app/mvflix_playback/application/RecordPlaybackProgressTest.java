package com.gcorp.service.app.mvflix_playback.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gcorp.service.app.mvflix_playback.application.port.PlaybackSessionRepository;
import com.gcorp.service.app.mvflix_playback.application.port.WatchProgressRepository;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.LibraryAssetReference;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RecordPlaybackProgressTest {
  private final PlaybackSessionRepository sessions = mock(PlaybackSessionRepository.class);
  private final WatchProgressRepository progress = mock(WatchProgressRepository.class);
  private final PlaybackOutbox outbox = mock(PlaybackOutbox.class);
  private final RecordPlaybackProgress useCase = new RecordPlaybackProgress(sessions, progress, outbox);
  private final PlaybackSessionId sessionId = new PlaybackSessionId(UUID.randomUUID());
  private final ViewerId viewer = new ViewerId("viewer-1");

  @Test
  void recordsProgressForOwner() {
    var session = session();
    when(sessions.findById(sessionId)).thenReturn(Mono.just(session));
    when(sessions.save(session)).thenReturn(Mono.just(session));
    when(progress.find(viewer, session.catalogItemId())).thenReturn(Mono.empty());
    when(progress.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
        Mono.just(invocation.getArgument(0)));
    when(outbox.append(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

    var saved = useCase.execute(sessionId, viewer, 1, 42, 100L, false).block();
    assertThat(saved.lastPosition().seconds()).isEqualTo(42);
  }

  @Test
  void rejectsAnotherViewer() {
    when(sessions.findById(sessionId)).thenReturn(Mono.just(session()));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> useCase.execute(sessionId, new ViewerId("other"), 1, 42, 100L, false).block())
        .isInstanceOf(RecordPlaybackProgress.PlaybackSessionForbiddenException.class);
  }

  @Test
  void ignoresRepeatedProgressWithoutSavingOrPublishing() {
    var session = session();
    session.recordProgress(new com.gcorp.service.app.mvflix_playback.domain.PlaybackPosition(42, 100), 1);
    when(sessions.findById(sessionId)).thenReturn(Mono.just(session));

    var result = useCase.execute(sessionId, viewer, 1, 42, 100L, false).block();

    assertThat(result).isSameAs(session);
    verify(sessions, never()).save(org.mockito.ArgumentMatchers.any());
    verify(progress, never()).find(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(outbox, never()).append(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void ignoresRepeatedCompletionWithoutSavingOrPublishing() {
    var session = session();
    session.complete(new com.gcorp.service.app.mvflix_playback.domain.PlaybackPosition(100, 100), 1);
    when(sessions.findById(sessionId)).thenReturn(Mono.just(session));

    var result = useCase.execute(sessionId, viewer, 1, 100, 100L, true).block();

    assertThat(result).isSameAs(session);
    verify(sessions, never()).save(org.mockito.ArgumentMatchers.any());
    verify(progress, never()).find(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(outbox, never()).append(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void savesSessionWithoutReplacingNewerGlobalWatchProgress() {
    var session = session();
    var watch = org.mockito.Mockito.mock(com.gcorp.service.app.mvflix_playback.domain.WatchProgress.class);
    when(sessions.findById(sessionId)).thenReturn(Mono.just(session));
    when(sessions.save(session)).thenReturn(Mono.just(session));
    when(progress.find(viewer, session.catalogItemId())).thenReturn(Mono.just(watch));
    when(watch.update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(sessionId),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq(false),
        org.mockito.ArgumentMatchers.any())).thenReturn(false);

    var result = useCase.execute(sessionId, viewer, 1, 42, 100L, false).block();

    assertThat(result).isSameAs(session);
    verify(sessions).save(session);
    verify(progress, never()).save(org.mockito.ArgumentMatchers.any());
    verify(outbox, never()).append(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  private PlaybackSession session() {
    return PlaybackSession.start(sessionId, viewer, new CatalogItemId(42), new LibraryAssetReference(77),
        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"));
  }
}
