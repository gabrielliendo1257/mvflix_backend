package com.gcorp.service.app.mvflix_movies.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemKind;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.metadata.MovieMetadata;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

@ActiveProfiles("sandbox")
@SpringBootTest
class UpdateCatalogItemUseCaseIntegrationTest extends PostgresIntegrationTest {

  private static final String TEST_CONSTRAINT = "test_movie_kind_must_remain_movie";

  @Autowired private UpdateCatalogItemUseCase useCase;
  @Autowired private CatalogItemRepository movieRepository;
  @Autowired private DatabaseClient databaseClient;

  @BeforeEach
  void setUp() {
    this.dropTestConstraint();
        this.databaseClient.sql("DELETE FROM catalog_items").fetch().rowsUpdated().block();
  }

  @AfterEach
  void tearDown() {
    this.dropTestConstraint();
  }

  @Test
  void doesNotPersistPartialDetailsWhenReclassificationFails() {
    CatalogItem movie =
        this.movieRepository
            .save(
                CatalogItem.createDraft(
                    "pepe", MovieMetadata.onlyTitle("Original title"), CatalogItemKind.MOVIE))
            .block();
    this.databaseClient
        .sql(
            "ALTER TABLE catalog_items ADD CONSTRAINT "
                + TEST_CONSTRAINT
                + " CHECK (kind = 'MOVIE')")
        .fetch()
        .rowsUpdated()
        .block();

    StepVerifier.create(this.useCase.execute(
        new CatalogActor("pepe", java.util.Set.of()), movie.getId(), switchToOther("Edited title")))
        .expectError(DataIntegrityViolationException.class)
        .verify();

    CatalogItem persisted = this.movieRepository.findById(movie.getId()).block();
    assertThat(persisted).isNotNull();
    assertThat(persisted.getKind()).isEqualTo(CatalogItemKind.MOVIE);
    assertThat(persisted.getTitle()).isEqualTo("Original title");
    assertThat(persisted.getMetadata().title()).isEqualTo("Original title");
  }

  private static UpdateCatalogItemCommand switchToOther(String title) {
    return new UpdateCatalogItemCommand(
        title,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        CatalogItemKind.VIDEO);
  }

  private void dropTestConstraint() {
    this.databaseClient
        .sql("ALTER TABLE catalog_items DROP CONSTRAINT IF EXISTS " + TEST_CONSTRAINT)
        .fetch()
        .rowsUpdated()
        .block();
  }
}
