package com.gcorp.service.app.mvflix_playback.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.gcorp.service.app.mvflix_playback.application.RecordPlaybackProgress;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import java.util.UUID;

class PlaybackControllerTest {
  @Test
  void mapsOptimisticLockConflictsToConflict() {
    var response = new PlaybackController(null, null).optimisticLockConflict();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void usesTheSameProblemResponseForClientErrors() {
    var controller = new PlaybackController(null, null);

    assertThat(controller.badRequest(new IllegalArgumentException("bad")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(controller.forbidden(new RecordPlaybackProgress.PlaybackSessionForbiddenException())
        .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(controller.notFound(new RecordPlaybackProgress.PlaybackSessionNotFoundException(
        new PlaybackSessionId(UUID.randomUUID()))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
