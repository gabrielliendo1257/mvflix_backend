package com.guille.media.bff.experience.media.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.guille.media.bff.experience.media.application.MediaDetail;
import com.guille.media.bff.experience.media.application.MediaDetailNotFoundException;
import com.guille.media.bff.experience.media.application.port.MediaDetailProjection;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Adapter del detalle: dos lecturas a movies (media + asset vinculado) bajo
 * el JWT del usuario, combinadas con zip. Aquí viven los espejos Jackson del
 * downstream; application recibe el modelo puro.
 */
@Component
public class MediaDetailProjectionAdapter implements MediaDetailProjection {

  private final WebClient moviesWebClient;

  public MediaDetailProjectionAdapter(
      @Qualifier("moviesWebClient") WebClient moviesWebClient) {
    this.moviesWebClient = moviesWebClient;
  }

  @Override
  public Mono<MediaDetail> detail(long mediaId) {
    var movie = this.moviesWebClient
        .get()
        .uri("/api/v1/movies/" + mediaId)
        .retrieve()
        .bodyToMono(DownstreamMovie.class)
        // movies responde 403 cuando la media no es visible (no revela
        // existencia); un 404 real queda como defensa.
        .onErrorMap(WebClientResponseException.class, error ->
            translate(mediaId, error));
    var asset = this.moviesWebClient
        .get()
        .uri("/api/v1/movies/media-assets/by-movie/" + mediaId)
        .retrieve()
        .bodyToMono(DownstreamAsset.class)
        .onErrorResume(WebClientResponseException.NotFound.class, error -> Mono.empty())
        .onErrorMap(WebClientResponseException.class,
            error -> translate(mediaId, error));

    return Mono.zip(movie, asset.defaultIfEmpty(DownstreamAsset.EMPTY))
        .map(joined -> MediaDetail.from(new MediaDetail.Source(
            mediaId,
            joined.getT1().title(),
            joined.getT1().originalTitle(),
            joined.getT1().year(),
            joined.getT1().duration(),
            joined.getT1().posterPath(),
            joined.getT1().overview(),
            joined.getT1().genres(),
            joined.getT1().director(),
            joined.getT1().cast(),
            joined.getT1().releaseDate(),
            joined.getT1().country(),
            joined.getT1().language(),
            joined.getT1().awards(),
            joined.getT1().popularity(),
            joined.getT1().kind(),
            joined.getT1().visibility(),
            joined.getT1().status(),
            joined.getT1().objectId(),
            joined.getT2().id(),
            joined.getT2().present(),
            tmdbId(joined.getT1()))));
  }

  private static Long tmdbId(DownstreamMovie movie) {
    return movie.tmdbId();
  }

  private static RuntimeException translate(long mediaId, WebClientResponseException error) {
    int status = error.getStatusCode().value();
    if (status == 404 || status == 403 || status == 401) {
      return new MediaDetailNotFoundException(mediaId);
    }
    return error;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DownstreamMovie(
      Long id,
      String title,
      String originalTitle,
      Integer year,
      String duration,
      @com.fasterxml.jackson.annotation.JsonProperty("poster_path") String posterPath,
      String overview,
       List<String> genres,
       String director,
       List<String> cast,
       @com.fasterxml.jackson.annotation.JsonProperty("release_date") String releaseDate,
       String country,
       String language,
       List<String> awards,
       Double popularity,
       String kind,
      String visibility,
      String status,
       @com.fasterxml.jackson.annotation.JsonProperty("enrichment_status") String enrichmentStatus,
       @com.fasterxml.jackson.annotation.JsonProperty("object_id") Long objectId,
       @com.fasterxml.jackson.annotation.JsonProperty("tmdb_id") Long tmdbId) {
    DownstreamMovie(long id, String title, String originalTitle, Integer year,
        String duration, String posterPath, String overview, List<String> genres,
        String director, List<String> cast, String kind, String visibility,
        String status, String enrichmentStatus, Long objectId, Long tmdbId) {
      this(id, title, originalTitle, year, duration, posterPath, overview, genres,
          director, cast, null, null, null, null, null, kind, visibility, status,
          enrichmentStatus, objectId, tmdbId);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DownstreamAsset(
      Long id,
      Boolean present) {

    static final DownstreamAsset EMPTY =
        new DownstreamAsset(null, null);
  }
}
