package com.guille.media.bff.experience.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.app.ports.MoviesWebClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

class CatalogSearchTest {

  private final MoviesWebClient movies = mock(MoviesWebClient.class);

  private CatalogSearch search;

  @BeforeEach
  void setUp() {
    this.search = new CatalogSearch(this.movies);
  }

  private MovieDto movie(Long id, String title, String originalTitle) {
    return new MovieDto(id, "READY", id, "PUBLIC", "MOVIE", title, originalTitle,
        1979, List.of(), null, null, null, List.of(), null, null, null, null,
        null, null, null);
  }

  @Test
  void filtersVisibleCatalogByTitleCaseInsensitive() {
    when(this.movies.listMovies(200))
        .thenReturn(Flux.just(
            movie(1L, "Alien", null),
            movie(2L, "Aliens", null),
            movie(3L, "Predator", null)));

    StepVerifier.create(this.search.search("ALIE"))
        .assertNext(result -> assertThat(result.title()).isEqualTo("Alien"))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void matchesOriginalTitleTooAndCapsResultsAt20() {
    var many = new java.util.ArrayList<MovieDto>();
    for (long i = 0; i < 25; i++) {
      many.add(movie(i, "Otro " + i, "Alien Redux"));
    }
    when(this.movies.listMovies(200)).thenReturn(Flux.fromIterable(many));

    StepVerifier.create(this.search.search("alien"))
        .expectNextCount(20)
        .verifyComplete();
  }

  @Test
  void blankQueryReturnsEmptyWithoutTouchingCatalog() {
    StepVerifier.create(this.search.search("   "))
        .verifyComplete();

    org.mockito.Mockito.verifyNoInteractions(this.movies);
  }
}
