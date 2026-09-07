package com.gcorp.service.app.mvflix_playback.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gcorp.service.app.mvflix_playback.application.port.PlaybackSessionRepository;
import com.gcorp.service.app.mvflix_playback.domain.AssetId;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RecordPlaybackProgressTest {
  private final PlaybackSessionRepository sessions = mock(PlaybackSessionRepository.class);
  private final RecordPlaybackProgress useCase = new RecordPlaybackProgress(sessions);
  private final PlaybackSessionId sessionId = new PlaybackSessionId(UUID.randomUUID());
  private final ViewerId viewer = new ViewerId("viewer-1");

  @Test
  void recordsProgressForOwner() {
    var session = session();
    when(sessions.findById(sessionId)).thenReturn(Mono.just(session));
    when(sessions.save(session)).thenReturn(Mono.just(session));

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

  private PlaybackSession session() {
    return PlaybackSession.start(sessionId, viewer, new CatalogItemId(42), new AssetId(77),
        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"));
  }
}
