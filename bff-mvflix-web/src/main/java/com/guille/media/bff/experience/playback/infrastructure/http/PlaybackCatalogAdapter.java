package com.guille.media.bff.experience.playback.infrastructure.http;

import com.guille.media.bff.app.dto.MediaAssetDto;
import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.experience.playback.application.AssetNotPlayableException;
import com.guille.media.bff.experience.playback.application.PlayableAsset;
import com.guille.media.bff.experience.playback.application.PlaybackContractViolationException;
import com.guille.media.bff.experience.playback.application.PlaybackForbiddenException;
import com.guille.media.bff.experience.playback.application.PlaybackMediaNotFoundException;
import com.guille.media.bff.experience.playback.application.PlaybackSourceUnavailableException;
import com.guille.media.bff.experience.playback.application.port.PlaybackCatalog;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

/**
 * Adapter del puerto {@link PlaybackCatalog} contra mvflix-movies. La
 * autorización REAL ocurre en movies ({@code isVisibleTo} bajo el JWT del
 * usuario, tanto al servir la media como el asset): aquí solo se traducen sus
 * respuestas a errores de la experiencia.
 *
 * <p>Composición de locators: el objectId (tabla {@code media} de movies)
 * define MANAGED; el MediaAsset identificado ({@code media_assets}) define
 * LOCAL. Ambos a la vez es un estado imposible del dominio y se rechaza como
 * violación de contrato en lugar de preferir uno en silencio.
 */
@Component
public class PlaybackCatalogAdapter implements PlaybackCatalog {

  private static final String API = "/api/v1/movies";

  private static final MediaAssetDto NO_ASSET = new MediaAssetDto(null, null, null, 0, null, null, null);

  private final WebClient moviesWebClient;

  public PlaybackCatalogAdapter(@Qualifier("playbackCatalogWebClient") WebClient moviesWebClient) {
    this.moviesWebClient = moviesWebClient;
  }

  @Override
  public Mono<PlaybackMedia> loadVisibleMedia(long mediaId) {
    return loadVisibleMedia(mediaId, null);
  }

  @Override
  public Mono<PlaybackMedia> loadVisibleMedia(long mediaId, String viewerId) {
    return this.moviesWebClient
        .get()
        .uri(API + "/" + mediaId + "/playback-context")
        .headers(headers -> { if (viewerId != null) headers.set("X-Viewer-Id", viewerId); })
        .retrieve()
        .bodyToMono(PlaybackContextDto.class)
        .onErrorMap(WebClientResponseException.class, error -> translateMovie(mediaId, error))
        .map(context -> compose(mediaId, context.movie(), context.asset()));
  }

  record PlaybackContextDto(Long id, String status, String title, String posterPath, String duration,
      Long objectId, MediaAssetDto asset) {
    MovieDto movie() {
      return new MovieDto(id, status, objectId, "PUBLIC", null, title, null, null, null,
          null, duration, null, null, null, posterPath, null, null, null, null, null);
    }
  }

  /** Puro y package-private para testearlo sin servidor HTTP. */
  static PlaybackMedia compose(long mediaId, MovieDto movieDto, MediaAssetDto assetDto) {
    var movie = new PlaybackMovie(
        movieDto.id(), movieDto.title(), movieDto.status(),
        movieDto.posterPath(), movieDto.duration(), movieDto.objectId());
    if (movieDto.objectId() != null && assetDto.id() != null) {
      throw new PlaybackContractViolationException(
          "La media " + mediaId + " declara objeto MANAGED y asset de biblioteca a la vez");
    }
    var asset = assetDto.id() == null ? null : new PlayableAsset(
        assetDto.id(), mediaId, assetDto.mimeType(), assetDto.size(),
        null, assetDto.libraryId(), assetDto.relativePath());
    return new PlaybackMedia(movie, asset);
  }

  private RuntimeException translateMovie(long mediaId, WebClientResponseException error) {
    int status = error.getStatusCode().value();
    if (status == 404) {
      return new PlaybackMediaNotFoundException(mediaId);
    }
    if (status == 403 || status == 401) {
      return new PlaybackForbiddenException(mediaId);
    }
    return unavailable(error);
  }

  private RuntimeException translate(long mediaId, WebClientResponseException error) {
    int status = error.getStatusCode().value();
    if (status == 403 || status == 401) {
      return new PlaybackForbiddenException(mediaId);
    }
    return unavailable(error);
  }

  static RuntimeException unavailable(WebClientResponseException error) {
    return new PlaybackSourceUnavailableException(
        "Catalogo no disponible (" + error.getStatusCode().value() + ")", error);
  }
}
