package com.gcorp.service.app.mvflix_playback.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.LibraryAssetReference;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackPosition;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import com.gcorp.service.app.mvflix_playback.domain.WatchProgress;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import reactor.core.publisher.Mono;

@Testcontainers(disabledWithoutDocker = true)
class PlaybackCasIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("mvflix_playback_test")
      .withUsername("test")
      .withPassword("test");

  private static R2dbcPlaybackSessionRepository sessions;
  private static R2dbcWatchProgressRepository progress;
  private static DatabaseClient database;
  private final ViewerId viewer = new ViewerId("viewer-1");
  private final CatalogItemId catalog = new CatalogItemId(42);

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration").load().migrate();
    var options = ConnectionFactoryOptions.builder()
        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
        .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
        .option(ConnectionFactoryOptions.PORT, POSTGRES.getFirstMappedPort())
        .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
        .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
        .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
        .build();
    database = DatabaseClient.create(ConnectionFactories.get(options));
    sessions = new R2dbcPlaybackSessionRepository(database);
    progress = new R2dbcWatchProgressRepository(database);
  }

  @BeforeEach
  void clean() {
    database.sql("TRUNCATE watch_progress, playback_session").fetch().rowsUpdated().block();
  }

  @Test
  void rejectsSameSessionSequenceAtDatabaseBoundary() {
    var id = new PlaybackSessionId(UUID.randomUUID());
    var session = session(id);
    session.recordProgress(new PlaybackPosition(10, 100L), 1);
    sessions.save(session).block();

    var duplicate = session(id);
    duplicate.recordProgress(new PlaybackPosition(10, 100L), 1);

    assertThatThrownBy(() -> sessions.save(duplicate).block())
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  void rejectsOutOfOrderSessionUpdate() {
    var id = new PlaybackSessionId(UUID.randomUUID());
    var current = session(id);
    current.recordProgress(new PlaybackPosition(20, 100L), 2);
    sessions.save(current).block();
    var older = session(id);
    older.recordProgress(new PlaybackPosition(10, 100L), 1);

    assertThatThrownBy(() -> sessions.save(older).block())
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  void allowsOnlyTheNewerConcurrentSessionToOwnGlobalProgress() {
    var older = watch(new PlaybackSessionId(UUID.randomUUID()), Instant.parse("2026-01-01T00:00:00Z"), 10);
    var newer = watch(new PlaybackSessionId(UUID.randomUUID()), Instant.parse("2026-01-01T00:01:00Z"), 20);

    var outcomes = Mono.zipDelayError(saveOutcome(older), saveOutcome(newer)).block();

    assertThat(outcomes.getT1() + outcomes.getT2()).isBetween(1, 2);
    assertThat(progress.find(viewer, catalog).block().lastSessionStartedAt())
        .isEqualTo(Instant.parse("2026-01-01T00:01:00Z"));
  }

  @Test
  void enforcesOneActiveSessionPerViewerAndContent() {
    sessions.save(session()).block();

    assertThatThrownBy(() -> sessions.save(session()).block())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private Mono<Integer> saveOutcome(WatchProgress value) {
    return progress.save(value).map(saved -> 1).onErrorResume(
        OptimisticLockingFailureException.class, error -> Mono.just(0));
  }

  private WatchProgress watch(PlaybackSessionId id, Instant startedAt, long position) {
    var value = new WatchProgress(viewer, catalog);
    value.update(new PlaybackPosition(position, 100L), id, startedAt, 1, false, Instant.now());
    return value;
  }

  private PlaybackSession session() {
    return session(new PlaybackSessionId(UUID.randomUUID()));
  }

  private PlaybackSession session(PlaybackSessionId id) {
    return PlaybackSession.start(id, viewer, catalog,
        new LibraryAssetReference(77), Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T01:00:00Z"));
  }

}
