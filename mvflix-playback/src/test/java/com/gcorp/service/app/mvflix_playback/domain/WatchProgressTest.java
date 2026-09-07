package com.gcorp.service.app.mvflix_playback.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WatchProgressTest {
  private final ViewerId viewer = new ViewerId("viewer-1");
  private final CatalogItemId catalog = new CatalogItemId(42);
  private final Instant firstSessionStartedAt = Instant.parse("2026-09-07T10:00:00Z");
  private final Instant secondSessionStartedAt = firstSessionStartedAt.plusSeconds(60);

  @Test
  void sequencesAreLocalToEachSession() {
    var progress = new WatchProgress(viewer, catalog);
    var firstSession = new PlaybackSessionId(UUID.randomUUID());
    var secondSession = new PlaybackSessionId(UUID.randomUUID());

    assertThat(progress.update(new PlaybackPosition(100, 1000), firstSession,
        firstSessionStartedAt, 100, false, Instant.now())).isTrue();
    assertThat(progress.update(new PlaybackPosition(1, 1000), secondSession,
        secondSessionStartedAt, 1, false, Instant.now())).isTrue();
    assertThat(progress.version()).isEqualTo(1);
    assertThat(progress.position().seconds()).isEqualTo(1);
  }

  @Test
  void rejectsLateUpdatesFromAnOlderSession() {
    var progress = new WatchProgress(viewer, catalog);
    var oldSession = new PlaybackSessionId(UUID.randomUUID());
    var newSession = new PlaybackSessionId(UUID.randomUUID());

    progress.update(new PlaybackPosition(100, 1000), oldSession, firstSessionStartedAt,
        100, false, Instant.now());
    progress.update(new PlaybackPosition(1, 1000), newSession, secondSessionStartedAt,
        1, false, Instant.now());

    assertThat(progress.update(new PlaybackPosition(101, 1000), oldSession,
        firstSessionStartedAt, 101, false, Instant.now())).isFalse();
    assertThat(progress.position().seconds()).isEqualTo(1);
    assertThat(progress.lastSessionId()).isEqualTo(newSession);
  }
}
