package com.gcorp.service.app.mvflix_activity.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectActivityEventTest {
  @Test
  void mapsLifecycleEventToMonotonicFeedStatus() {
    var event = event("MediaIngestionFailed");

    assertThat(event.status()).isEqualTo("FAILED");
  }

  @Test
  void rejectsUnsupportedEventFamilies() {
    assertThatThrownBy(() -> event("MovieCreated"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ProjectActivityEvent event(String type) {
    return new ProjectActivityEvent(UUID.randomUUID(), type, 1, Instant.now(),
        "producer", "actor", "audience", UUID.randomUUID(), "MediaIngestion", "id",
        JsonNodeFactory.instance.objectNode());
  }
}
