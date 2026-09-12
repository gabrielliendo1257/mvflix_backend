package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.experience.addmedia.application.port.AddMediaProcessRepository;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaMovies;
import com.guille.media.bff.experience.addmedia.model.AddMediaProcess;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaStorage;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaCompensationRepository;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaCompensationRepository.Kind;
import com.guille.media.bff.experience.addmedia.model.AddMediaId;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;
import com.guille.media.bff.experience.addmedia.model.InvalidAddMediaTransition;
import com.guille.media.bff.experience.addmedia.application.AddMediaResult;
import com.guille.media.bff.shared.error.EntityNotFound;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;

import reactor.core.publisher.Mono;

/**
 * Cancelación race-safe: PRIMERO reclama CANCELLING (CAS) para bloquear
 * complete/cancel competidores, DESPUÉS compensa recursos, al final persiste
 * CANCELLED. El descarte del draft solo aplica mientras WAITING_FOR_UPLOAD:
 * en VERIFYING el contenido ya fue verificado por storage.
 */
@Slf4j
@Service
public class CancelAddMedia {
  public CancelAddMedia(MediaIngestionClient ingestion) {
    this.processes = null;
    this.storage = null;
    this.movies = null;
    this.compensations = null;
    this.ingestion = ingestion;
    this.ingestionEnabled = true;
  }

  private final AddMediaProcessRepository processes;
  private final AddMediaStorage storage;
  private final AddMediaMovies movies;
  private final AddMediaCompensationRepository compensations;
  private final MediaIngestionClient ingestion;
  private final boolean ingestionEnabled;

  public CancelAddMedia(AddMediaProcessRepository processes, AddMediaStorage storage,
      AddMediaMovies movies) {
    this(processes, storage, movies,
        processes instanceof AddMediaCompensationRepository repository ? repository : null);
  }

  public CancelAddMedia(AddMediaProcessRepository processes, AddMediaStorage storage,
      AddMediaMovies movies, AddMediaCompensationRepository compensations) {
    this.processes = processes;
    this.storage = storage;
    this.movies = movies;
    this.compensations = compensations;
    this.ingestion = null; this.ingestionEnabled = false;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public CancelAddMedia(AddMediaProcessRepository processes, AddMediaStorage storage, AddMediaMovies movies,
      AddMediaCompensationRepository compensations, MediaIngestionClient ingestion,
      @org.springframework.beans.factory.annotation.Value("${features.add-media.media-ingestion-enabled:false}") boolean ingestionEnabled) {
    this.processes = processes; this.storage = storage; this.movies = movies; this.compensations = compensations;
    this.ingestion = ingestion; this.ingestionEnabled = ingestionEnabled;
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId) {
    return handle(ownerSubject, addMediaId, "add-media:" + addMediaId);
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId, String correlationId) {
    if (this.ingestionEnabled) {
      return this.ingestion.cancel(ownerSubject, addMediaId, correlationId)
          .map(MediaIngestionResultMapper::map);
    }
    return this.processes
        .findById(new AddMediaId(addMediaId))
        .filter(process -> process.ownedBy(ownerSubject))
        .switchIfEmpty(Mono.error(new EntityNotFound("Proceso no encontrado")))
        .flatMap(this::cancelIfAllowed);
  }

  private Mono<AddMediaResult> cancelIfAllowed(AddMediaProcess process) {
    if (process.phase() == AddMediaPhase.READY
        || process.phase() == AddMediaPhase.CANCELLED
        || process.phase() == AddMediaPhase.FAILED
        || process.phase() == AddMediaPhase.STARTING
        || process.phase() == AddMediaPhase.PREPARING) {
      return Mono.error(new InvalidAddMediaTransition(
          process.phase(), AddMediaPhase.CANCELLING));
    }
    return this.processes
        .tryCancelClaim(process.id())
        .flatMap(claimed -> {
          if (!claimed) {
            // READY/CANCELLED/FAILED ganó la carrera: nada que compensar.
            return Mono.error(new InvalidAddMediaTransition(
                process.phase(), AddMediaPhase.CANCELLING));
          }
          AddMediaProcess cancelling = process.cancelling();
          return this.compensate(process)
              .flatMap(compensation -> compensation.pending()
                  ? Mono.just(AddMediaResult.from(cancelling))
                  : this.processes.save(cancelling.cancelled()).map(AddMediaResult::from));
        });
  }

  /**
   * Compensaciones best-effort. VERIFYING no implica contenido verificado
   * (también representa Storage PENDING): cualquier proceso anterior a
   * FINALIZING se compensa completo (upload + draft). Los fallos quedan
   * registrados como compensaciones pendientes.
   */
  private Mono<CompensationResult> compensate(AddMediaProcess process) {
    Mono<Boolean> cancelUpload = process.uploadId() == null
        ? Mono.just(false)
        : this.storage.cancelUpload(process.uploadId()).thenReturn(false)
            .onErrorResume(err -> enqueue(process, Kind.CANCEL_UPLOAD, process.uploadId(), err)
                .thenReturn(true));
    Mono<Boolean> discardDraft = process.movieId() == null
        ? Mono.just(false)
        : this.movies.discardDraft(process.movieId()).thenReturn(false)
            .onErrorResume(err -> enqueue(process, Kind.DISCARD_DRAFT, process.movieId(), err)
                .thenReturn(true));
    return cancelUpload
        .zipWith(discardDraft)
        .map(result -> new CompensationResult(result.getT1() || result.getT2()));
  }

  private record CompensationResult(boolean pending) {}

  private Mono<Void> enqueue(AddMediaProcess process, Kind kind, Long resourceId, Throwable error) {
    if (compensations == null) {
      return Mono.error(error);
    }
    log.error("add-media compensation pending process={} kind={} resource={}",
        process.id().value(), kind, resourceId, error);
    return compensations.enqueue(process.id(), kind, resourceId, error);
  }
}
