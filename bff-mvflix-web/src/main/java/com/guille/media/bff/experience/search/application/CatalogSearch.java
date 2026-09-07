package com.guille.media.bff.experience.search.application;

import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.app.ports.MoviesWebClient;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import java.util.Locale;

/**
 * Búsqueda GLOBAL de catálogo: responde "¿qué contenido puedo encontrar o
 * reproducir?" filtrando el catálogo VISIBLE del usuario por título.
 *
 * <p>Distinta de la búsqueda de candidatos TMDB (Add Media), que responde
 * "¿qué película representa este archivo?".
 *
 * <p>V1 filtra en memoria sobre el listado visible (que Movies ya autoriza);
 * si el catálogo escala, el filtro baja a Movies vía parámetro de consulta y
 * esto se promueve a contexto propio.
 */
@Service
@RequiredArgsConstructor
public class CatalogSearch {

  private static final int CANDIDATE_POOL = 200;
  private static final int MAX_RESULTS = 20;

  private final MoviesWebClient movies;

  public Flux<SearchResult> search(String query) {
    String needle = normalize(query);
    if (needle.isBlank()) {
      return Flux.empty();
    }
    return this.movies
        .listMovies(CANDIDATE_POOL)
        .filter(movie -> containsIgnoreCase(movie.title(), needle)
            || containsIgnoreCase(movie.originalTitle(), needle))
        .map(CatalogSearch::toResult)
        .take(MAX_RESULTS);
  }

  private static SearchResult toResult(MovieDto movie) {
    return new SearchResult(movie.id(), movie.title(), movie.originalTitle(), movie.year(),
        movie.posterPath(), movie.kind(), movie.status(), "READY".equals(movie.status()));
  }

  private static boolean containsIgnoreCase(String haystack, String needle) {
    return haystack != null
        && haystack.toLowerCase(Locale.ROOT).contains(needle);
  }

  private static String normalize(String query) {
    return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
  }
}
