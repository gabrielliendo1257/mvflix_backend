package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.experience.addmedia.application.port.AddMediaProcessRepository;
import com.guille.media.bff.experience.addmedia.application.port.AddMediaStorage;
import com.guille.media.bff.experience.addmedia.model.AddMediaId;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;
import com.guille.media.bff.experience.addmedia.model.AddMediaProcess;
import com.guille.media.bff.shared.error.EntityNotFound;
import com.guille.media.bff.experience.addmedia.application.AddMediaResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;

import reactor.core.publisher.Mono;

/**
 * Estado del proceso para la UX. Si el upload sigue WAITING_FOR_UPLOAD,
 * restaura las instrucciones de subida con un presigned FRESCO: el navegador
 * que recargó la pantalla puede subir sin crear otra sesión.
 */
@Slf4j
@Service
public class GetAddMediaStatus {

  private final AddMediaProcessRepository processes;
  private final AddMediaStorage storage;
  private final MediaIngestionClient ingestion;
  private final boolean ingestionEnabled;

  public GetAddMediaStatus(AddMediaProcessRepository processes, AddMediaStorage storage) {
    this(processes, storage, null, false);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public GetAddMediaStatus(AddMediaProcessRepository processes, AddMediaStorage storage,
      MediaIngestionClient ingestion,
      @org.springframework.beans.factory.annotation.Value("${features.add-media.media-ingestion-enabled:false}") boolean ingestionEnabled) {
    this.processes = processes; this.storage = storage; this.ingestion = ingestion; this.ingestionEnabled = ingestionEnabled;
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId) {
    return handle(ownerSubject, addMediaId, "add-media:" + addMediaId);
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId, String correlationId) {
    if (this.ingestionEnabled) {
      return this.ingestion.status(ownerSubject, addMediaId, correlationId)
          .map(MediaIngestionResultMapper::map);
    }
    return this.processes
        .findById(new AddMediaId(addMediaId))
        .filter(process -> process.ownedBy(ownerSubject))
        .switchIfEmpty(Mono.error(new EntityNotFound("Proceso no encontrado")))
        .flatMap(this::withFreshInstructions);
  }

  private Mono<AddMediaResult> withFreshInstructions(AddMediaProcess process) {
    if (process.phase() != AddMediaPhase.WAITING_FOR_UPLOAD || process.uploadId() == null) {
      return Mono.just(AddMediaResult.from(process));
    }
    return this.storage
        .refreshInstructions(process.uploadId())
        .map(session -> AddMediaResult.waitingForUpload(process, session))
        .onErrorResume(error -> {
          log.warn("add-media status: no se pudo renovar URL {}: {}",
              process.uploadId(), error.getMessage());
          return Mono.just(AddMediaResult.from(process));
        });
  }
}
