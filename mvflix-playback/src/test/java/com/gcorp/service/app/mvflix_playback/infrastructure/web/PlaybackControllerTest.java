package com.gcorp.service.app.mvflix_playback.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PlaybackControllerTest {
  @Test
  void mapsOptimisticLockConflictsToConflict() {
    var response = new PlaybackController(null, null).optimisticLockConflict();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }
}
