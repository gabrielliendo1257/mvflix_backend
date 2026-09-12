package com.guille.media.bff.experience.addmedia.web;

import com.guille.media.bff.app.service.WebSessionService;
import com.guille.media.bff.experience.addmedia.application.CancelAddMedia;
import com.guille.media.bff.experience.addmedia.application.CompleteProcessAddMedia;
import com.guille.media.bff.experience.addmedia.application.GetAddMediaStatus;
import com.guille.media.bff.experience.addmedia.application.PreviewMovieCandidate;
import com.guille.media.bff.experience.addmedia.application.SearchMovieCandidates;
import com.guille.media.bff.experience.addmedia.application.StartAddMedia;
import com.guille.media.bff.app.dto.MultipartUploadDtos;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.RestController;

import com.guille.media.bff.app.dto.MovieEnrichmentPreviewDto;
import com.guille.media.bff.app.dto.MovieEnrichmentSearchDto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Experiencia Add Media: el front dice "añade este contenido" y no aprende cómo están divididos los
 * microservicios.
 */
@Tag(name = "Add Media", description = "Alta guiada de contenido: candidatos, proceso con idempotencia, subida directa y cierre")
@RestController
@Validated
@RequestMapping(value = "/web/add-media", produces = MediaType.APPLICATION_JSON_VALUE)
public class AddMediaController {

  private final SearchMovieCandidates searchMovieCandidates;
  private final PreviewMovieCandidate previewMovieCandidate;
  private final StartAddMedia startAddMedia;
  private final CompleteProcessAddMedia completeProcess;
  private final CancelAddMedia cancelAddMedia;
  private final GetAddMediaStatus getStatus;
  private final WebSessionService session;

  public AddMediaController(
      SearchMovieCandidates searchMovieCandidates,
      PreviewMovieCandidate previewMovieCandidate,
      StartAddMedia startAddMedia,
      CompleteProcessAddMedia completeProcess,
      CancelAddMedia cancelAddMedia,
      GetAddMediaStatus getStatus,
      WebSessionService session) {
    this.searchMovieCandidates = searchMovieCandidates;
    this.previewMovieCandidate = previewMovieCandidate;
    this.startAddMedia = startAddMedia;
    this.completeProcess = completeProcess;
    this.cancelAddMedia = cancelAddMedia;
    this.getStatus = getStatus;
    this.session = session;
  }

  @Operation(summary = "Busca candidatos en TMDB por título (y año opcional)")
  @GetMapping("/candidates")
  public Flux<MovieEnrichmentSearchDto> candidates(
      @RequestParam String query, @RequestParam(required = false) Integer year) {
    return this.searchMovieCandidates.search(query, year);
  }

  @Operation(summary = "Detalle del candidato seleccionado antes de iniciar")
  @GetMapping("/candidates/{providerId}")
  public Mono<MovieEnrichmentPreviewDto> candidate(@PathVariable Long providerId) {
    return this.previewMovieCandidate.preview(providerId);
  }

  @Operation(summary = "Inicia el alta: draft identificado + sesión de upload (idempotente)")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<AddMediaResponse>> start(
      @Valid @RequestBody StartAddMediaRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyHeader,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
    if (idempotencyHeader != null && idempotencyHeader.length() > 128) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Idempotency-Key must be at most 128 characters"));
    }
    String idempotencyKey = idempotencyHeader == null || idempotencyHeader.isBlank()
        ? request.idempotencyKey() : idempotencyHeader;
    return this.ownerSubject()
        .flatMap(owner -> this.startAddMedia.handle(owner, request.toCommand(idempotencyKey), correlationId == null
            ? "add-media:" + idempotencyKey : correlationId))
        .map(result -> ResponseEntity.status(HttpStatus.CREATED)
            .body(AddMediaResponse.from(result)));
  }

  @Operation(summary = "Estado del proceso; restaura instrucciones frescas mientras espera upload")
  @GetMapping("/{addMediaId}")
  public Mono<AddMediaResponse> status(
      @PathVariable String addMediaId,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
    return this.ownerSubject()
        .flatMap(owner -> this.getStatus.handle(owner, addMediaId, correlationId == null
            ? "add-media:" + addMediaId : correlationId))
        .map(AddMediaResponse::from);
  }

  /**
   * Cierre del alta. 200 si quedó READY, 202 VERIFYING_UPLOAD si storage aún
   * verifica, 409 ante veredicto definitivo (rollback ya ejecutado).
   */
  @PostMapping(value = "/{addMediaId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<AddMediaResponse>> complete(
      @PathVariable String addMediaId,
      @RequestBody(required = false) CompleteSizeRequest request,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
    Long sizeBytes = request == null ? null : request.sizeBytes();
    return this.ownerSubject()
        .flatMap(owner -> request != null && request.parts() != null
            ? this.completeProcess.handleMultipart(owner, addMediaId, sizeBytes, request.parts(),
                correlationId == null ? "add-media:" + addMediaId : correlationId)
            : this.completeProcess.handle(owner, addMediaId, sizeBytes,
                correlationId == null ? "add-media:" + addMediaId : correlationId))
        .map(result -> result.phase() == com.guille.media.bff.experience.addmedia.model.AddMediaPhase.READY
            ? ResponseEntity.ok(AddMediaResponse.from(result))
            : ResponseEntity.accepted().body(AddMediaResponse.from(result)));
  }

  /** Cancelación del proceso con compensaciones acotadas. */
  @Operation(summary = "Cancela el proceso y compensa recursos ya creados")
  @PostMapping("/{addMediaId}/cancel")
  public Mono<AddMediaResponse> cancel(@PathVariable String addMediaId,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
    return this.ownerSubject()
        .flatMap(owner -> this.cancelAddMedia.handle(owner, addMediaId, correlationId == null
            ? "add-media:" + addMediaId : correlationId))
        .map(AddMediaResponse::from);
  }

  public record CompleteSizeRequest(Long sizeBytes,
      java.util.List<MultipartUploadDtos.CompletedPart> parts) {}

  /**
   * Sujeto propietario del proceso. Fallback "sandbox" SOLO para el perfil sin
   * auth: en producción el subject viene del token de sesión OAuth2 y un
   * anónimo recibe 401 por la cadena de seguridad antes de llegar aquí.
   */
  private Mono<String> ownerSubject() {
    return this.session.currentSubject().defaultIfEmpty("sandbox");
  }
}
