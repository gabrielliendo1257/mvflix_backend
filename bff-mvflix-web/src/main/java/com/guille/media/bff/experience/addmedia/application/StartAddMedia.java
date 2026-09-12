package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.app.dto.UploadCreateRequest;
import com.guille.media.bff.app.dto.UploadSessionDto;
import com.guille.media.bff.app.dto.UserProfile;
import com.guille.media.bff.app.ports.UsersWebPort;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaCompensationRepository;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaCompensationRepository.Kind;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaMovies;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaMovies.IdentifiedDraft;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaProcessRepository;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaStorage;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;
import com.guille.media.bff.experience.addmedia.model.AddMediaProcess;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Inicio idempotente del alta: primero el DRAFT en Movies (dueño del catálogo y de la política de
 * usuario bloqueado), después la reserva en Storage.
 *
 * <p>Compensación: si Storage falla tras crear el draft, se descarta SOLO el draft creado por este
 * proceso y el error se propaga; el proceso queda en STARTING para que un replay con la misma
 * idempotencyKey reintente limpio. Sin transacción distribuida: compensaciones explícitas y
 * acotadas.
 */
@Slf4j
@Service
public class StartAddMedia {

  private final AddMediaMovies movies;
  private final AddMediaStorage storage;
  private final AddMediaProcessRepository processes;
  private final UsersWebPort users;
  private final AddMediaCompensationRepository compensations;
  private final MediaIngestionClient ingestion;
  private final boolean ingestionEnabled;

  public StartAddMedia(
      AddMediaMovies movies,
      AddMediaStorage storage,
      AddMediaProcessRepository processes,
      UsersWebPort users) {
    this(
        movies,
        storage,
        processes,
        users,
        processes instanceof AddMediaCompensationRepository repository ? repository : null);
  }

  public StartAddMedia(
      AddMediaMovies movies,
      AddMediaStorage storage,
      AddMediaProcessRepository processes,
      UsersWebPort users,
      AddMediaCompensationRepository compensations) {
    this.movies = movies;
    this.storage = storage;
    this.processes = processes;
    this.users = users;
    this.compensations = compensations;
    this.ingestion = null;
    this.ingestionEnabled = false;
  }

  @Autowired
  public StartAddMedia(
      AddMediaMovies movies,
      AddMediaStorage storage,
      AddMediaProcessRepository processes,
      UsersWebPort users,
      AddMediaCompensationRepository compensations,
      MediaIngestionClient ingestion,
      @Value("${features.add-media.media-ingestion-enabled:false}") boolean ingestionEnabled) {
    this.movies = movies;
    this.storage = storage;
    this.processes = processes;
    this.users = users;
    this.compensations = compensations;
    this.ingestion = ingestion;
    this.ingestionEnabled = ingestionEnabled;
  }

  private static Long strictParseUploadId(String uploadId) {
    if (uploadId == null || !uploadId.chars().allMatch(Character::isDigit)) {
      throw new InvalidStorageResponseException(
          "Storage devolvió un uploadId no válido: " + uploadId);
    }
    try {
      return Long.valueOf(uploadId);
    } catch (NumberFormatException e) {
      throw new InvalidStorageResponseException(
          "Storage devolvió un uploadId no válido: " + uploadId);
    }
  }

  /** Huella canónica del intento; pública para que los tests usen la MISMA. */
  static String fingerprintOf(StartAddMediaCommand command) {
    return RequestFingerprint.of(
        Map.of(
            "file", command.file(),
            "movie",
                Map.of(
                    "providerId",
                    command.movie().providerId() == null ? "" : command.movie().providerId(),
                    "draft",
                    command.movie().draft()),
            "access", accessOf(command)));
  }

  private static StartAddMediaCommand.InitialAccess accessOf(StartAddMediaCommand command) {
    return command.access() == null
        ? new StartAddMediaCommand.InitialAccess("PRIVATE", java.util.List.of())
        : command.access();
  }

  public Mono<AddMediaResult> handle(String ownerSubject, StartAddMediaCommand command) {
    return handle(ownerSubject, command, "add-media:" + command.idempotencyKey());
  }

  public Mono<AddMediaResult> handle(
      String ownerSubject, StartAddMediaCommand command, String correlationId) {
    if (this.ingestionEnabled) {
      return this.users
          .me()
          .flatMap(
              profile ->
                  this.guardBlocked(profile)
                      .then(
                          Mono.defer(
                              () -> this.ingestion.create(ownerSubject, command, correlationId))))
          .map(MediaIngestionResultMapper::map);
    }
    return this.processes
        .createIfAbsent(ownerSubject, command.idempotencyKey(), fingerprintOf(command))
        .flatMap(
            process -> {
              if (process.phase() == AddMediaPhase.WAITING_FOR_UPLOAD
                  && process.uploadId() != null) {
                // Replay tras recarga/pérdida de la primera respuesta: restaurar
                // las instrucciones de subida con un presigned fresco.
                return this.storage
                    .refreshInstructions(process.uploadId())
                    .map(session -> AddMediaResult.waitingForUpload(process, session))
                    .onErrorResume(
                        error -> {
                          log.warn(
                              "add-media replay: no se pudo renovar URL para {}: {}",
                              process.id(),
                              error.getMessage());
                          return Mono.just(AddMediaResult.from(process));
                        });
              }
              if (process.phase() != AddMediaPhase.STARTING) {
                log.info("add-media replay: process={} phase={}", process.id(), process.phase());
                return Mono.just(AddMediaResult.from(process));
              }
              // Claim atómico: solo un request ejecuta los side effects.
              return this.processes
                  .tryClaim(process.id())
                  .flatMap(
                      claimed -> {
                        if (!claimed) {
                          // Otro request está preparando: el front consulta estado.
                          log.info(
                              "add-media: proceso {} reclamado por otro request", process.id());
                          return this.processes.findById(process.id()).map(AddMediaResult::from);
                        }
                        // Continuar sobre la instancia ya reclamada (PREPARING).
                        return this.processes
                            .findById(process.id())
                            .flatMap(
                                claimedProcess ->
                                    this.startFresh(claimedProcess, command)
                                        .onErrorResume(
                                            error ->
                                                this.processes
                                                    .releaseClaim(process.id())
                                                    .then(Mono.error(error))));
                      });
            });
  }

  /**
   * Gate de experiencia: un usuario bloqueado por violaciones no inicia altas. La política vive en
   * users; aquí solo se traduce a un error amigable antes de gastar recursos en drafts/uploads.
   */
  private Mono<AddMediaResult> startFresh(AddMediaProcess process, StartAddMediaCommand command) {
    return this.users
        .me()
        .flatMap(
            profile ->
                this.guardBlocked(profile)
                    // defer: si el gate falla, la continuación ni se construye.
                    .then(Mono.defer(() -> this.createDraftAndUpload(process, command))));
  }

  private Mono<Void> guardBlocked(UserProfile profile) {
    if (profile.blocked()) {
      log.warn(
          "add-media bloqueado: usuario={} violaciones={}",
          profile.username(),
          profile.violations());
      return Mono.error(
          new UserBlockedException(
              profile.username() == null ? "?" : profile.username(), profile.violations()));
    }
    return Mono.empty();
  }

  private Mono<AddMediaResult> createDraftAndUpload(
      AddMediaProcess process, StartAddMediaCommand command) {
    // Default EXPLÍCITO: la ausencia de intención es PRIVATE.
    // Default EXPLÍCITO: la ausencia de intención es PRIVATE.
    var access =
        command.access() == null
            ? new StartAddMediaCommand.InitialAccess("PRIVATE", java.util.List.of())
            : command.access();
    IdentifiedDraft identified =
        new IdentifiedDraft(
            command.movie().draft(),
            command.movie().providerId(),
            access.visibility() == null ? "PRIVATE" : access.visibility(),
            access.sharedWith(),
            command.idempotencyKey() + ":create-catalog-draft");
    return this.movies
        .createIdentifiedDraft(identified)
        .flatMap(
            draft ->
                this.persistDraftReference(process, draft.id())
                    .flatMap(prepared -> this.prepareUpload(prepared, draft.id(), command))
                    // El upload ya se compensó dentro de prepareUpload (si
                    // llegó a existir); aquí solo queda el draft.
                    .onErrorResume(
                        error ->
                            this.compensateDraft(process.id(), draft.id(), error)
                                .then(Mono.error(error))));
  }

  private Mono<AddMediaProcess> persistDraftReference(AddMediaProcess process, Long movieId) {
    return this.processes.savePreparing(process.withMovieId(movieId));
  }

  private Mono<AddMediaResult> prepareUpload(
      AddMediaProcess process, Long movieId, StartAddMediaCommand command) {
    UploadCreateRequest uploadRequest =
        new UploadCreateRequest(
            command.file().filename(),
            command.file().sizeBytes(),
            command.file().mimeType(),
            process.id().value());
    return this.storage
        .prepareUpload(uploadRequest)
        .flatMap(
            session -> {
              Long uploadId = strictParseUploadId(session.uploadId());
              // Con uploadId no fiable NO se persiste: se compensa todo.
              AddMediaProcess prepared = process.uploadPrepared(movieId, uploadId);
              return this.persistPrepared(prepared, session)
                  .onErrorResume(
                      err ->
                          this.compensateUpload(process.id(), uploadId, err).then(Mono.error(err)))
                  .doOnNext(
                      view ->
                          log.info(
                              "add-media started: process={} movie={} upload={}",
                              view.addMediaId(),
                              view.movieId(),
                              view.uploadId()));
            });
  }

  private Mono<AddMediaResult> persistPrepared(AddMediaProcess process, UploadSessionDto session) {
    return this.processes
        .save(process)
        .map(saved -> AddMediaResult.waitingForUpload(saved, session));
  }

  private Mono<Void> compensateUpload(
      com.guille.media.bff.experience.addmedia.model.AddMediaId processId,
      Long uploadId,
      Throwable cause) {
    log.warn(
        "add-media compensation process={} kind={} resource={}",
        processId.value(),
        Kind.CANCEL_UPLOAD,
        uploadId,
        cause);
    return this.storage
        .cancelUpload(uploadId)
        .onErrorResume(err -> enqueue(processId, Kind.CANCEL_UPLOAD, uploadId, err));
  }

  private Mono<Void> compensateDraft(
      com.guille.media.bff.experience.addmedia.model.AddMediaId processId,
      Long movieId,
      Throwable cause) {
    log.warn(
        "add-media compensation process={} kind={} resource={}",
        processId.value(),
        Kind.DISCARD_DRAFT,
        movieId,
        cause);
    return this.movies
        .discardDraft(movieId)
        .onErrorResume(err -> enqueue(processId, Kind.DISCARD_DRAFT, movieId, err));
  }

  private Mono<Void> enqueue(
      com.guille.media.bff.experience.addmedia.model.AddMediaId processId,
      Kind kind,
      Long resourceId,
      Throwable error) {
    if (this.compensations == null) {
      return Mono.error(error);
    }
    return this.compensations
        .enqueue(processId, kind, resourceId, error)
        .doOnSuccess(
            ignored ->
                log.error(
                    "add-media compensation persisted process={} kind={} resource={}",
                    processId.value(),
                    kind,
                    resourceId,
                    error));
  }
}
