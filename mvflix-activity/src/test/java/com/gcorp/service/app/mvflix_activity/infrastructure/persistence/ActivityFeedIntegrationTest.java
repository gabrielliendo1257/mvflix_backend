package com.gcorp.service.app.mvflix_activity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gcorp.service.app.mvflix_activity.feed.application.ProjectActivityCommand;
import com.gcorp.service.app.mvflix_activity.feed.application.ProjectActivityEvent;
import com.gcorp.service.app.mvflix_activity.feed.application.CatalogItemAccessChangedCommand;
import com.gcorp.service.app.mvflix_activity.feed.application.ProjectCatalogItemAccessChanged;
import com.gcorp.service.app.mvflix_activity.feed.application.ProjectUploadFailed;
import com.gcorp.service.app.mvflix_activity.feed.application.UploadFailedCommand;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

@SpringBootTest(properties = {"spring.main.web-application-type=none", "mvflix.messaging.kafka.enabled=false"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ActivityFeedIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
        + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName());
    registry.add("spring.r2dbc.username", POSTGRES::getUsername);
    registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
        + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName());
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired ActivityPersistence persistence;
  @Autowired ProjectActivityEvent projector;
  @Autowired ProjectCatalogItemAccessChanged catalogAccessProjector;
  @Autowired ProjectUploadFailed uploadFailedProjector;
  @Autowired DatabaseClient database;
  @MockBean ReactiveJwtDecoder jwtDecoder;

  @AfterEach
  void clean() {
    database.sql("DELETE FROM activity_feed").fetch().rowsUpdated().block();
    database.sql("DELETE FROM activity_inbox").fetch().rowsUpdated().block();
  }

  @Test
  void duplicateEventIsProjectedOnce() {
    var event = event("MediaIngestionCompleted", UUID.randomUUID(), "audience", Instant.now());

    projector.handle(event).block();
    projector.handle(event).block();

    assertThat(persistence.feed("audience", null, 20).collectList().block()).hasSize(1);
  }

  @Test
  void lateStartedEventDoesNotRegressCompletedStatus() {
    UUID correlation = UUID.randomUUID();
    projector.handle(event("MediaIngestionCompleted", correlation, "audience", Instant.now())).block();
    projector.handle(event("MediaIngestionStarted", correlation, "audience", Instant.now().plusSeconds(1))).block();

    var entries = persistence.feed("audience", null, 20).collectList().block();
    assertThat(entries).singleElement().extracting(e -> e.status()).isEqualTo("COMPLETED");
  }

  @Test
  void isolatesAudiencesAndGroupsByCorrelation() {
    UUID correlation = UUID.randomUUID();
    projector.handle(event("MediaIngestionStarted", correlation, "one", Instant.now())).block();
    projector.handle(event("MediaIngestionCompleted", correlation, "one", Instant.now())).block();
    projector.handle(event("MediaIngestionCompleted", UUID.randomUUID(), "two", Instant.now())).block();

    assertThat(persistence.feed("one", null, 20).collectList().block()).hasSize(1);
    assertThat(persistence.feed("one", null, 20).collectList().block()).singleElement()
        .extracting(e -> e.status()).isEqualTo("COMPLETED");
    assertThat(persistence.feed("two", null, 20).collectList().block()).hasSize(1);
  }

  @Test
  void cursorContinuesAfterTheNewestEntry() {
    projector.handle(event("MediaIngestionCompleted", UUID.randomUUID(), "audience",
        Instant.parse("2026-01-01T00:00:02Z"))).block();
    projector.handle(event("MediaIngestionCompleted", UUID.randomUUID(), "audience",
        Instant.parse("2026-01-01T00:00:01Z"))).block();

    var first = persistence.feed("audience", null, 1).collectList().block();
    var second = persistence.feed("audience", first.get(0).cursor(), 1).collectList().block();
    assertThat(first).hasSize(1);
    assertThat(second).hasSize(1);
    assertThat(second.get(0).correlationId()).isNotEqualTo(first.get(0).correlationId());
  }

  @Test
  void projectionFailureRollsBackProjectionAndLeavesInboxFailed() {
    var source = event("MediaIngestionFailed", UUID.randomUUID(), "audience", Instant.now());
    var event = new ProjectActivityCommand(source.eventId(), source.eventType(), source.eventVersion(),
        source.occurredAt(), source.producer(), source.actorId(), source.audienceId(), source.correlationId(),
        source.aggregateType(), source.aggregateId(), source.fileName(), source.catalogItemId(), "x".repeat(121));

    assertThatThrownBy(() -> projector.handle(event).block()).isInstanceOf(RuntimeException.class);
    assertThat(persistence.feed("audience", null, 20).collectList().block()).isEmpty();
    assertThat(database.sql("SELECT status FROM activity_inbox WHERE event_id=:id").bind("id", event.eventId())
        .map((row, metadata) -> row.get("status", String.class)).one().block()).isEqualTo("FAILED");
  }

  @Test
  void projectsCatalogAccessDetailsIntoTheFeed() {
    UUID eventId = UUID.randomUUID();
    var event = new CatalogItemAccessChangedCommand(eventId, "CatalogItemAccessChanged", 1,
        Instant.parse("2026-09-01T16:00:00Z"), "mvflix-movies", "user-123", "user-123",
        UUID.randomUUID(), "CatalogItem", "42", 42L, "MOVIE", "Interstellar", "PRIVATE",
        "SHARED", 0, 3);

    catalogAccessProjector.handle(event).block();

    var entry = persistence.feed("user-123", null, 20).collectList().block();
    assertThat(entry).singleElement().satisfies(activity -> {
      assertThat(activity.type()).isEqualTo("CATALOG_ACCESS");
      assertThat(activity.status()).isEqualTo("ACCESS_CHANGED");
      assertThat(activity.category()).isEqualTo("CATALOG");
      assertThat(activity.resourceType()).isEqualTo("CatalogItem");
      assertThat(activity.resourceId()).isEqualTo("42");
      assertThat(activity.resourceTitle()).isEqualTo("Interstellar");
       assertThat(activity.context()).containsEntry("previousSharedCount", 0)
           .containsEntry("sharedCount", 3);
    });
  }

  @Test
  void keepsBulkCatalogAccessEventsSeparateWhenCorrelationIsShared() {
    UUID correlation = UUID.randomUUID();
    var first = catalogAccessEvent(UUID.randomUUID(), correlation, "42", 42L);
    var second = catalogAccessEvent(UUID.randomUUID(), correlation, "43", 43L);

    catalogAccessProjector.handle(first).block();
    catalogAccessProjector.handle(second).block();

    assertThat(persistence.feed("user-123", null, 20).collectList().block())
        .hasSize(2)
        .extracting(activity -> activity.resourceId())
        .containsExactlyInAnyOrder("42", "43");
  }

  @Test
  void projectsUploadFailureAsErrorForItsAudience() {
    UUID eventId = UUID.randomUUID();
    String reason = "INSUFFICIENT_STORAGE\nrequired=1000" + (char) 1;
    var event = new UploadFailedCommand(eventId, "UploadFailed", 1,
        Instant.parse("2026-09-01T16:00:00Z"), "mvflix-storage", "system", "user-123",
         eventId, "ManagedObject", "7", 7L, "user-123", "movie.mp4", reason);

    uploadFailedProjector.handle(event).block();

    assertThat(persistence.feed("user-123", null, 20).collectList().block())
        .singleElement().satisfies(activity -> {
           assertThat(activity.type()).isEqualTo("UPLOAD_FAILED");
           assertThat(activity.severity()).isEqualTo("ERROR");
           assertThat(activity.category()).isEqualTo("STORAGE");
           assertThat(activity.context().get("reason"))
               .isEqualTo(reason);
         });
  }

  private static CatalogItemAccessChangedCommand catalogAccessEvent(UUID eventId,
      UUID correlation, String aggregateId, long catalogItemId) {
    return new CatalogItemAccessChangedCommand(eventId, "CatalogItemAccessChanged", 1,
        Instant.parse("2026-09-01T16:00:00Z"), "mvflix-movies", "user-123", "user-123",
        correlation, "CatalogItem", aggregateId, catalogItemId, "MOVIE", "Interstellar",
        "PRIVATE", "SHARED", 0, 3);
  }

  private static ProjectActivityCommand event(String type, UUID correlation, String audience, Instant occurred) {
    return new ProjectActivityCommand(UUID.randomUUID(), type, 1, occurred, "mvflix-media-ingestion", "actor", audience,
        correlation, "MediaIngestion", correlation.toString(), "movie.mp4", 42L, null);
  }
}
