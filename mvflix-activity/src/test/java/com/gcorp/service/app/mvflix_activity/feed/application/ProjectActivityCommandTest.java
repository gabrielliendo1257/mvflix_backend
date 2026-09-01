package com.gcorp.service.app.mvflix_activity.feed.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectActivityCommandTest {
  @Test
  void rejectsUnexpectedProducer() {
    UUID ingestionId = UUID.randomUUID();

    assertThatThrownBy(() -> command("other-producer", "MediaIngestion", ingestionId.toString(), ingestionId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnexpectedAggregateType() {
    UUID ingestionId = UUID.randomUUID();

    assertThatThrownBy(() -> command("mvflix-media-ingestion", "Movie", ingestionId.toString(), ingestionId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAggregateIdThatDoesNotMatchIngestion() {
    UUID ingestionId = UUID.randomUUID();

    assertThatThrownBy(() -> command("mvflix-media-ingestion", "MediaIngestion",
        UUID.randomUUID().toString(), ingestionId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ProjectActivityCommand command(String producer, String aggregateType,
      String aggregateId, UUID ingestionId) {
    return new ProjectActivityCommand(UUID.randomUUID(), "MediaIngestionCompleted", 1,
        Instant.parse("2026-01-01T12:00:00Z"), producer, "actor", "audience", ingestionId,
        aggregateType, aggregateId, "movie.mp4", 42L, null);
  }
}
