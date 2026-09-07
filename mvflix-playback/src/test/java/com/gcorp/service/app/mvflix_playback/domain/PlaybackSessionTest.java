package com.gcorp.service.app.mvflix_playback.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlaybackSessionTest {
  private final Instant startedAt = Instant.parse("2026-09-06T15:00:00Z");

  @Test
  void acceptsOnlyNewProgressSequences() {
    PlaybackSession session = session();

    assertThat(session.recordProgress(new PlaybackPosition(1250, 7200), 18)).isTrue();
    assertThat(session.recordProgress(new PlaybackPosition(1200, 7200), 18)).isFalse();
    assertThat(session.recordProgress(new PlaybackPosition(1200, 7200), 17)).isFalse();
    assertThat(session.lastPosition().seconds()).isEqualTo(1250);
  }

  @Test
  void rejectsProgressAfterCompletionAndCompletesIdempotently() {
    PlaybackSession session = session();
    PlaybackPosition position = new PlaybackPosition(7200, 7200);

    assertThat(session.complete(position, 1)).isTrue();
    assertThat(session.complete(position, 1)).isFalse();

    assertThat(session.status()).isEqualTo(PlaybackSessionStatus.COMPLETED);
    assertThat(session.recordProgress(position, 1)).isFalse();
  }

  @Test
  void expiresOnlyWhenTheSessionDeadlineHasArrived() {
    PlaybackSession session = session();

    session.expire(startedAt.plusSeconds(3599));
    assertThat(session.status()).isEqualTo(PlaybackSessionStatus.ACTIVE);
    session.expire(startedAt.plusSeconds(3600));
    assertThat(session.status()).isEqualTo(PlaybackSessionStatus.EXPIRED);
  }

  @Test
  void enforcesPositionBoundsWithTolerance() {
    assertThatThrownBy(() -> new PlaybackPosition(-1, 7200))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PlaybackPosition(7250, 7200))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private PlaybackSession session() {
    return PlaybackSession.start(new PlaybackSessionId(java.util.UUID.randomUUID()),
        new ViewerId("viewer-1"), new CatalogItemId(42), new LibraryAssetReference(7), startedAt,
        startedAt.plusSeconds(3600));
  }
}
