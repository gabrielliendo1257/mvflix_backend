package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.app.dto.CompleteMovieRequest;
import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.app.dto.UploadStatusDto;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaMovies;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaStorage;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;


import reactor.core.publisher.Mono;

/**
 * Flujo de cierre del alta de contenido: verifica el estado REAL del upload en
 * storage, clasifica un veredicto y ejecuta rollback + penalidad cuando
 * corresponde.
 *
 * <p>Capa limpia: solo puertos propios y excepciones de aplicación. La
 * traducción de errores HTTP/WebClient vive en los adapters.
 */
@Slf4j
@Service
@Deprecated(forRemoval = true)
public class CompleteAddMedia {

  private static final int PENDING_RETRIES = 3;

  private final AddMediaMovies movies;
  private final AddMediaStorage storage;
  private final com.guille.media.bff.app.ports.UsersWebPort users;

  public CompleteAddMedia(
      AddMediaMovies movies,
      AddMediaStorage storage,
      com.guille.media.bff.app.ports.UsersWebPort users) {
    this.movies = movies;
    this.storage = storage;
    this.users = users;
  }


  /**
   * Complete orquestado: idempotente (si ya está READY responde sin tocar nada)
   * y con veredicto clasificado a partir del estado real del storage.
   */
  public Mono<UploadCompletionOutcome> complete(Long movieId, CompleteMovieRequest request) {
    log.info("complete: movie={} storageId={} sizeBytes={}",
        movieId, request.storageId(), request.sizeBytes());
    return this.movies
        .getMovie(movieId)
        .flatMap(movie -> {
          if ("READY".equals(movie.status())) {
            log.info("complete: movie={} ya READY, no-op idempotente", movieId);
            return Mono.just((UploadCompletionOutcome)
                new UploadCompletionOutcome.Completed(movie));
          }
          return this.completeFromDraft(movieId, request);
        });
  }

  private Mono<UploadCompletionOutcome> completeFromDraft(Long movieId,
      CompleteMovieRequest request) {
    return this.storage
        .requestCompletion(request.storageId())
        .then(this.storage.getUploadState(request.storageId()))
        .flatMap(status -> this.evaluateStatus(movieId, request, status))
        .onErrorResume(UploadVerdictException.class,
            ex -> this.rollback(movieId, request, ex.getCode(), ex.getMessage(), true));
  }

  private Mono<UploadCompletionOutcome> evaluateStatus(Long movieId,
      CompleteMovieRequest request, UploadStatusDto status) {
    String state = status.status() == null ? "" : status.status();
    log.debug("add-media complete: movie={} estado storage={} key={}",
        movieId, state, status.storageKey());
    switch (state) {
      case "COMPLETED":
        if (request.sizeBytes() != null
            && status.object() != null
            && status.object().expectedSize() > 0
            && !request.sizeBytes().equals(status.object().expectedSize())) {
          log.warn("complete: movie={} tamaño no cuadra: esperado={} reportado={}",
              movieId, status.object().expectedSize(), request.sizeBytes());
          return Mono.error(new UploadVerdictException("UPLOAD_INCONSISTENT",
              "El tamaño del objeto subido no coincide con lo reportado por el front"));
        }
        return this.persistReady(movieId, request, status);
      case "PENDING":
        log.info("complete: movie={} upload aún PENDING; verificación asíncrona", movieId);
        Long uploadId = this.toStorageId(status.uploadId());
        return Mono.just(new UploadCompletionOutcome.StillVerifying(uploadId));
      case "FAILED":
        log.warn("complete: movie={} storage FAILED: objeto descartado o en cuarentena",
            movieId);
        return Mono.error(new UploadVerdictException("UPLOAD_FAILED",
            "La subida fue marcada como fallida por el storage"));
      default:
        log.warn("complete: movie={} estado inesperado={}", movieId, state);
        return Mono.error(new UploadVerdictException("UPLOAD_INCONSISTENT",
            "Estado inesperado del storage: " + state));
    }
  }

  private Mono<UploadCompletionOutcome> persistReady(Long movieId, CompleteMovieRequest request,
      UploadStatusDto status) {
    log.info("complete: movie={} veredicto OK, persistiendo READY con object_key={}",
        movieId, status.storageKey());
    return this.movies
        .completeDraft(movieId, this.toStorageId(status.uploadId()), status.storageKey())
        .doOnSuccess(movie -> log.info("complete: movie={} READY persistida", movieId))
        .map((MovieDto movie) -> (UploadCompletionOutcome)
            new UploadCompletionOutcome.Completed(movie))
        .onErrorResume(DownstreamUnavailableException.class,
            ex -> this.onMoviesDownstreamError(movieId, ex))
        .onErrorResume(DownstreamRejectionException.class,
            ex -> this.onMoviesCompleteError(movieId, request, ex.status()));
  }

  private Long toStorageId(String uploadId) {
    try {
      return uploadId == null ? null : Long.valueOf(uploadId);
    } catch (NumberFormatException e) {
      log.warn("complete: uploadId no numerico={}, object_id quedará null", uploadId);
      return null;
    }
  }

  private Mono<UploadCompletionOutcome> onMoviesCompleteError(Long movieId, CompleteMovieRequest request,
      int downstreamStatus) {
    if (downstreamStatus == 404) {
      log.warn("complete: movie={} no existe al persistir; rollback de objeto", movieId);
      return this.rollback(movieId, request, "MOVIE_MISSING",
          "La película no existe al momento del complete", false);
    }
    if (downstreamStatus == 409) {
      // Reconciliación: otro camino pudo haber completado la película.
      return this.movies
          .getMovie(movieId)
          .flatMap(movie -> "READY".equals(movie.status())
              ? Mono.just((UploadCompletionOutcome)
                  new UploadCompletionOutcome.Completed(movie))
              : Mono.<UploadCompletionOutcome>error(new UploadVerdictException(
                  "UPLOAD_CONFLICT",
                  "La película no pudo completarse: estado " + movie.status())))
          .switchIfEmpty(Mono.error(new UploadVerdictException("MOVIE_MISSING",
              "La película no existe al reconciliar el conflicto")));
    }
    return this.downstreamUnavailable(movieId, downstreamStatus);
  }

  private Mono<UploadCompletionOutcome> onMoviesDownstreamError(Long movieId,
      DownstreamUnavailableException ex) {
    log.error("complete: movie={} servicio aguas abajo caído: {}", movieId, ex.getMessage());
    return Mono.error(ex);
  }

  /** Rollback: elimina la película + el objeto (restaura cuota). Opcionalmente penaliza. */
  private Mono<UploadCompletionOutcome> rollback(Long movieId, CompleteMovieRequest request,
      String code, String reason, boolean penalize) {
    log.warn("ROLLBACK movie={} storageId={} code={} reason={}",
        movieId, request.storageId(), code, reason);
    return Mono.when(
            this.movies.discardDraft(movieId).onErrorResume(
                err -> this.logRollbackFailure("película " + movieId, err)),
            this.storage.deleteObject(request.storageId()).onErrorResume(
                err -> this.logRollbackFailure("objeto storageId=" + request.storageId(), err)))
        .then(Mono.defer(() -> {
          if (penalize) {
            return this.users
                .reportViolation(code + ": " + reason)
                .doOnSuccess(v -> log.warn("ROLLBACK: violación registrada para movie={} ({})",
                    movieId, code))
                .onErrorResume(err -> this.logRollbackFailure("violación de movie " + movieId,
                    err))
                .then(Mono.error(new VerdictAppliedException(code, reason)));
          }
          return Mono.error(new VerdictAppliedException(code, reason));
        }));
  }

  private Mono<UploadCompletionOutcome> downstreamUnavailable(Long movieId, int status) {
    log.error("complete: movie={} servicio aguas abajo no disponible: status={}", movieId, status);
    return Mono.error(new DownstreamUnavailableException(status,
        "DOWNSTREAM_UNAVAILABLE", "Servicio aguas abajo no disponible: " + status));
  }

  private Mono<Void> logRollbackFailure(String what, Throwable err) {
    log.error("ROLLBACK: fallo al borrar {}: {}", what, err.getMessage());
    return Mono.empty();
  }
}
