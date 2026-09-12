package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.app.dto.CompleteMovieRequest;
import com.guille.media.bff.app.dto.MultipartUploadDtos.CompletedPart;
import com.guille.media.bff.experience.addmedia.application.UploadCompletionOutcome.Completed;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaProcessRepository;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import com.guille.media.bff.experience.addmedia.model.AddMediaId;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;
import com.guille.media.bff.experience.addmedia.model.AddMediaProcess;
import com.guille.media.bff.experience.addmedia.model.InvalidAddMediaTransition;
import com.guille.media.bff.app.dto.MultipartUploadDtos;
import com.guille.media.bff.app.ports.StorageWebClient;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Cierre de la experiencia Add Media. SERIALIZACIÓN: complete reclama
 * FINALIZING (CAS) ANTES de tocar Movies/Storage; si cancel ganó el claim,
 * complete falla con 409 sin side effects.
 *
 * <p>Salidas del claim FINALIZING:
 * <ul>
 *   <li>Movie persistida → READY;</li>
 *   <li>Storage aún PENDING o fallo transitorio → VERIFYING (reintentable);</li>
 *   <li>veredicto definitivo → FAILED (rollback ya ejecutado).</li>
 * </ul>
 */
@Slf4j
@Service
public class CompleteProcessAddMedia {

  private final AddMediaProcessRepository processes;
  private final CompleteAddMedia completion;
  private final MediaIngestionClient ingestion;
  private final StorageWebClient storage;
  private final boolean ingestionEnabled;

  public CompleteProcessAddMedia(AddMediaProcessRepository processes, CompleteAddMedia completion) {
    this(processes, completion, null, false, null);
  }

  public CompleteProcessAddMedia(AddMediaProcessRepository processes, CompleteAddMedia completion,
      MediaIngestionClient ingestion, boolean ingestionEnabled) {
    this(processes, completion, ingestion, ingestionEnabled, null);
  }

  @Autowired
  public CompleteProcessAddMedia(AddMediaProcessRepository processes, CompleteAddMedia completion,
      MediaIngestionClient ingestion,
      @Value("${features.add-media.media-ingestion-enabled:false}") boolean ingestionEnabled,
      StorageWebClient storage) {
    this.processes = processes; this.completion = completion; this.ingestion = ingestion;
    this.storage = storage;
    this.ingestionEnabled = ingestionEnabled;
  }

  /** Completes storage multipart before allowing ingestion to finalize the catalog item. */
  public Mono<AddMediaResult> handleMultipart(String ownerSubject, String addMediaId,
      Long reportedSizeBytes, List<CompletedPart> parts,
      String correlationId) {
    if (!this.ingestionEnabled || this.ingestion == null || this.storage == null) {
      return Mono.error(new IllegalStateException("multipart add-media is unavailable"));
    }
    return this.ingestion.status(ownerSubject, addMediaId, correlationId)
        .flatMap(view -> {
          if (!"PRESIGNED_MULTIPART".equals(view.strategy()) || view.uploadId() == null) {
            return Mono.error(new IllegalArgumentException("add-media upload is not multipart"));
          }
          if ("COMPLETED".equals(view.phase())) {
            return Mono.just(MediaIngestionResultMapper.map(view));
          }
          return this.storage.completeMultipart(view.uploadId(), new MultipartUploadDtos.Complete(parts))
              .then(this.ingestion.complete(ownerSubject, addMediaId, reportedSizeBytes, correlationId))
              .map(MediaIngestionResultMapper::map);
        });
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId, Long reportedSizeBytes) {
    return handle(ownerSubject, addMediaId, reportedSizeBytes, "add-media:" + addMediaId);
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId, Long reportedSizeBytes,
      String correlationId) {
    if (this.ingestionEnabled) {
      return this.ingestion.complete(ownerSubject, addMediaId, reportedSizeBytes, correlationId)
          .map(MediaIngestionResultMapper::map);
    }
    return this.processes
        .findById(new AddMediaId(addMediaId))
        .filter(process -> process.ownedBy(ownerSubject))
        .switchIfEmpty(Mono.error(new com.guille.media.bff.shared.error.EntityNotFound(
            "Proceso no encontrado")))
        .flatMap(process -> {
          // Idempotencia: READY responde siempre la misma vista.
          if (process.phase() == AddMediaPhase.READY) {
            return Mono.just(AddMediaResult.from(process));
          }
          if (process.phase() == AddMediaPhase.CANCELLED
              || process.phase() == AddMediaPhase.CANCELLING
              || process.phase() == AddMediaPhase.FAILED
              || process.phase() == AddMediaPhase.STARTING
              || process.phase() == AddMediaPhase.PREPARING) {
            return Mono.error(new InvalidAddMediaTransition(
                process.phase(), AddMediaPhase.FINALIZING));
          }
          return this.claimAndComplete(process, reportedSizeBytes);
        });
  }

  private Mono<AddMediaResult> legacyHandle(AddMediaProcess process, Long reportedSizeBytes) {
    if (process.phase() == AddMediaPhase.READY) return Mono.just(AddMediaResult.from(process));
    if (process.phase() == AddMediaPhase.CANCELLED || process.phase() == AddMediaPhase.CANCELLING
        || process.phase() == AddMediaPhase.FAILED || process.phase() == AddMediaPhase.STARTING
        || process.phase() == AddMediaPhase.PREPARING) {
      return Mono.error(new InvalidAddMediaTransition(process.phase(), AddMediaPhase.FINALIZING));
    }
    return this.claimAndComplete(process, reportedSizeBytes);
  }

  private Mono<AddMediaResult> claimAndComplete(AddMediaProcess process, Long reportedSizeBytes) {
    return this.processes
        .tryFinalizeClaim(process.id())
        .flatMap(claimed -> {
          if (!claimed) {
            log.info("add-media: finalize perdió la carrera para {}", process.id());
            return Mono.error(new InvalidAddMediaTransition(
                process.phase(), AddMediaPhase.FINALIZING));
          }
          return this.processes
              .findById(process.id())
              .flatMap(finalizing -> this.runCompletion(finalizing, reportedSizeBytes));
        });
  }

  private Mono<AddMediaResult> runCompletion(AddMediaProcess finalizing, Long reportedSizeBytes) {
    return this.completion
        .complete(finalizing.movieId(),
            new CompleteMovieRequest(finalizing.uploadId(), reportedSizeBytes))
        .flatMap(outcome -> {
          if (outcome instanceof Completed completed) {
            return this.processes
                .save(finalizing.ready())
                .map(saved -> withMovie(AddMediaResult.from(saved), completed.movie()));
          }
          // PENDING en storage o fallo transitorio: soltar el claim y permitir retry.
          return this.processes
              .save(finalizing.backToVerifying())
              .map(saved -> withFailureCode(AddMediaResult.from(saved), null));
        })
        .onErrorResume(VerdictAppliedException.class,
            verdict -> this.processes
                .save(finalizing.failed(verdict.getCode()))
                .then(Mono.error(verdict)))
        .onErrorResume(error -> {
              // Cualquier otro fallo (incluido CAS perdido por cancel):
              // devolver el claim a VERIFYING salvo que el proceso ya no sea
              // FINALIZING (cancel ganó).
              return this.processes.findById(finalizing.id())
                  .filter(current -> current.phase() == AddMediaPhase.FINALIZING)
                  .flatMap(current -> this.processes.save(current.backToVerifying()))
                  .then(Mono.error(error));
            });
  }

  private static AddMediaResult withFailureCode(AddMediaResult view, String code) {
    return new AddMediaResult(view.addMediaId(), view.ownerSubject(), view.phase(),
        view.movieId(), view.uploadId(), view.upload(), code);
  }

  private static AddMediaResult withMovie(AddMediaResult view,
      com.guille.media.bff.app.dto.MovieDto movie) {
    return new AddMediaResult(view.addMediaId(), view.ownerSubject(),
        com.guille.media.bff.experience.addmedia.model.AddMediaPhase.READY,
        movie.id(), view.uploadId(), view.upload(), view.failureCode());
  }

}
