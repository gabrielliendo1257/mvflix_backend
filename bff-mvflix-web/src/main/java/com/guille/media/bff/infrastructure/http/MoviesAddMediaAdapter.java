package com.guille.media.bff.infrastructure.http;

import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.app.dto.MovieEnrichmentPreviewDto;
import com.guille.media.bff.app.dto.MovieEnrichmentSearchDto;
import com.guille.media.bff.app.ports.MoviesWebClient;
import com.guille.media.bff.experience.addmedia.application.DownstreamRejectionException;
import com.guille.media.bff.experience.addmedia.application.DownstreamUnavailableException;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaMovies;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Adapter HTTP del contexto Add Media hacia movies-service. Delega en el cliente existente. */
@Component
@RequiredArgsConstructor
public class MoviesAddMediaAdapter implements AddMediaMovies {

  private final MoviesWebClient delegate;

  @Override
  public Flux<MovieEnrichmentSearchDto> searchCandidates(String query, Integer year) {
    return this.translate(this.delegate.searchCandidates(query, year));
  }

  @Override
  public Mono<MovieEnrichmentPreviewDto> previewCandidate(Long providerId) {
    return this.translate(this.delegate.previewCandidate(providerId));
  }

  @Override
  public Mono<MovieDto> createIdentifiedDraft(IdentifiedDraft command) {
    return this.translate(
        this.delegate.createIdentifiedDraft(
            command.draft(),
            command.tmdbId(),
            command.visibility(),
            command.sharedWith(),
            command.idempotencyKey()));
  }

  @Override
  public Mono<MovieDto> getMovie(Long movieId) {
    return this.translate(this.delegate.movieById(movieId));
  }

  @Override
  public Mono<MovieDto> completeDraft(Long movieId, Long objectId, String objectKey) {
    return this.translate(this.delegate.completeMovie(movieId, objectId, objectKey));
  }

  @Override
  public Mono<Void> discardDraft(Long movieId) {
    return this.translate(this.delegate.deleteMovie(movieId));
  }

  /**
   * Frontera de traducción: los errores HTTP/WebClient no cruzan hacia la aplicación. 5xx y fallos
   * de conexión son CAÍDAS reintentables; los 4xx se preservan como rechazo con status para
   * decisiones (404/409).
   */
  private <T> Flux<T> translate(Flux<T> call) {
    return call.onErrorResume(
            WebClientResponseException.class,
            ex -> {
              if (ex.getStatusCode().is5xxServerError()) {
                return Mono.error(
                    new DownstreamUnavailableException(
                        ex.getStatusCode().value(), "DOWNSTREAM_UNAVAILABLE", ex.getMessage()));
              }
              return Mono.error(
                  new DownstreamRejectionException(ex.getStatusCode().value(), ex.getMessage()));
            })
        .onErrorResume(
            WebClientRequestException.class,
            ex ->
                Mono.error(
                    new DownstreamUnavailableException(
                        503, "DOWNSTREAM_UNREACHABLE", ex.getMessage())));
  }

  private <T> Mono<T> translate(Mono<T> call) {
    return call.onErrorResume(
            WebClientResponseException.class,
            ex -> {
              if (ex.getStatusCode().is5xxServerError()) {
                return Mono.error(
                    new DownstreamUnavailableException(
                        ex.getStatusCode().value(), "DOWNSTREAM_UNAVAILABLE", ex.getMessage()));
              }
              return Mono.error(
                  new DownstreamRejectionException(ex.getStatusCode().value(), ex.getMessage()));
            })
        .onErrorResume(
            WebClientRequestException.class,
            ex ->
                Mono.error(
                    new DownstreamUnavailableException(
                        503, "DOWNSTREAM_UNREACHABLE", ex.getMessage())));
  }
}
