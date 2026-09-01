package com.guille.media.bff.presenter.api;

import com.guille.media.bff.app.service.StreamTicketException;
import com.guille.media.bff.experience.addmedia.application.DownstreamRejectionException;
import com.guille.media.bff.experience.addmedia.application.DownstreamUnavailableException;
import com.guille.media.bff.experience.addmedia.application.IdempotencyConflictException;
import com.guille.media.bff.experience.addmedia.application.InvalidIntentException;
import com.guille.media.bff.experience.addmedia.application.InvalidStorageResponseException;
import com.guille.media.bff.experience.addmedia.application.UserBlockedException;
import com.guille.media.bff.experience.addmedia.application.VerdictAppliedException;
import com.guille.media.bff.experience.playback.application.AssetNotPlayableException;
import com.guille.media.bff.experience.playback.application.LocalStreamTokenException;
import com.guille.media.bff.experience.playback.application.PlaybackContractViolationException;
import com.guille.media.bff.experience.playback.application.PlaybackForbiddenException;
import com.guille.media.bff.experience.playback.application.PlaybackMediaNotFoundException;
import com.guille.media.bff.experience.playback.application.PlaybackSourceUnavailableException;
import com.guille.media.bff.shared.error.EntityNotFound;
import com.guille.media.bff.experience.addmedia.model.InvalidAddMediaTransition;
import com.guille.media.bff.presenter.api.dto.OrchestrationError;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

  /** Ticket de stream inválido/expirado: el <video> debe pedir uno nuevo. */
  @ExceptionHandler(StreamTicketException.class)
  public Mono<ResponseEntity<OrchestrationError>> streamTicket(StreamTicketException ex) {
    log.warn("Stream ticket rechazado: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.UNAUTHORIZED.value(), "STREAM_TICKET_INVALID", ex.getMessage())));
  }

  /** Playback: la media no existe (movies no revela existencia ajena). */
  @ExceptionHandler(PlaybackMediaNotFoundException.class)
  public Mono<ResponseEntity<OrchestrationError>> playbackMediaNotFound(
      PlaybackMediaNotFoundException ex) {
    log.warn("Playback media no encontrada: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.NOT_FOUND.value(), "MEDIA_NOT_FOUND", ex.getMessage())));
  }

  /** Media Detail: la media no existe o no es visible para el sujeto. */
  @ExceptionHandler(com.guille.media.bff.experience.media.application.MediaDetailNotFoundException.class)
  public Mono<ResponseEntity<OrchestrationError>> mediaDetailNotFound(
      com.guille.media.bff.experience.media.application.MediaDetailNotFoundException ex) {
    log.warn("Media detail no encontrada: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.NOT_FOUND.value(), "MEDIA_NOT_FOUND", ex.getMessage())));
  }

  /** Playback: el usuario autenticado no puede reproducir la media. */
  @ExceptionHandler(PlaybackForbiddenException.class)
  public Mono<ResponseEntity<OrchestrationError>> playbackForbidden(
      PlaybackForbiddenException ex) {
    log.warn("Playback denegado: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.FORBIDDEN.value(), "PLAYBACK_FORBIDDEN", ex.getMessage())));
  }

  /** Playback: la media existe y es visible, pero hoy no tiene contenido reproducible. */
  @ExceptionHandler(AssetNotPlayableException.class)
  public Mono<ResponseEntity<OrchestrationError>> assetNotPlayable(
      AssetNotPlayableException ex) {
    log.warn("Playback sin contenido reproducible: code={} message={}",
        ex.getCode(), ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.CONFLICT.value(), ex.getCode(), ex.getMessage())));
  }

  /** Playback: storage/acceso al contenido temporalmente no disponible. */
  @ExceptionHandler(PlaybackSourceUnavailableException.class)
  public Mono<ResponseEntity<OrchestrationError>> sourceUnavailable(
      PlaybackSourceUnavailableException ex) {
    log.warn("Playback fuente no disponible: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.SERVICE_UNAVAILABLE.value(), "SOURCE_UNAVAILABLE", ex.getMessage())));
  }

  /** Capability de stream LOCAL inválida/expirada: el player pide sesión nueva. */
  @ExceptionHandler(LocalStreamTokenException.class)
  public Mono<ResponseEntity<OrchestrationError>> localStreamToken(
      LocalStreamTokenException ex) {
    log.warn("Capability local de playback rechazada: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.UNAUTHORIZED.value(), "STREAM_ACCESS_INVALID", ex.getMessage())));
  }

  /** Playback: el catálogo envió locators contradictorios (estado imposible). */
  @ExceptionHandler(PlaybackContractViolationException.class)
  public Mono<ResponseEntity<OrchestrationError>> contractViolation(
      PlaybackContractViolationException ex) {
    log.error("Violación de contrato del catálogo en playback: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.BAD_GATEWAY.value(), "PLAYBACK_CONTRACT_VIOLATION", ex.getMessage())));
  }

  /** Recurso de aplicación inexistente (p.ej. proceso Add Media ajeno). */
  @ExceptionHandler(EntityNotFound.class)
  public Mono<ResponseEntity<OrchestrationError>> entityNotFound(EntityNotFound ex) {
    log.warn("Recurso no encontrado: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.NOT_FOUND.value(), "NOT_FOUND", ex.getMessage())));
  }

  /** Veredicto definitivo ya aplicado (rollback ejecutado). */
  @ExceptionHandler(VerdictAppliedException.class)
  public Mono<ResponseEntity<OrchestrationError>> verdict(VerdictAppliedException ex) {
    log.warn("Veredicto aplicado: code={} message={}", ex.getCode(), ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.CONFLICT.value(), ex.getCode(), ex.getMessage())));
  }

  /**
   * Rechazo 4xx de un servicio aguas abajo (validación, no encontrado,
    * conflicto): se propaga el status con un mensaje estable sin filtrar
    * detalles internos.
   */
  @ExceptionHandler(DownstreamRejectionException.class)
  public Mono<ResponseEntity<OrchestrationError>> downstreamRejection(
      DownstreamRejectionException ex) {
    log.warn("Aguas abajo rechazó la petición: status={}", ex.status());
    return Mono.just(
        ResponseEntity.status(ex.status())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                ex.status(), "DOWNSTREAM_REJECTED",
                "La solicitud fue rechazada por un servicio dependiente")));
  }

  /** Caída reintentable de un servicio aguas abajo. */
  @ExceptionHandler(DownstreamUnavailableException.class)
  public Mono<ResponseEntity<OrchestrationError>> downstream(
      DownstreamUnavailableException ex) {
    log.warn("Aguas abajo caído: {} {}", ex.getCode(), ex.getMessage());
    return Mono.just(
        ResponseEntity.status(ex.getUpstreamStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                ex.getUpstreamStatus(), ex.getCode(), ex.getMessage())));
  }

  /** Usuario bloqueado por la política de users. */
  @ExceptionHandler(UserBlockedException.class)
  public Mono<ResponseEntity<OrchestrationError>> userBlocked(UserBlockedException ex) {
    log.warn("Usuario bloqueado: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.FORBIDDEN.value(), "USER_BLOCKED", ex.getMessage())));
  }

  /** Contrato violado por un servicio aguas abajo. */
  @ExceptionHandler(InvalidStorageResponseException.class)
  public Mono<ResponseEntity<OrchestrationError>> invalidStorage(
      InvalidStorageResponseException ex) {
    log.error("Respuesta inválida de storage: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.BAD_GATEWAY.value(), "INVALID_UPLOAD_RESPONSE", ex.getMessage())));
  }

  /** Intención incompleta: faltan datos obligatorios del candidato. */
  @ExceptionHandler(InvalidIntentException.class)
  public Mono<ResponseEntity<OrchestrationError>> invalidIntent(InvalidIntentException ex) {
    log.warn("Intención de Add Media inválida: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.BAD_REQUEST.value(), "INVALID_INTENT", ex.getMessage())));
  }

  /** Misma idempotencyKey con payload distinto: conflicto, no silencio. */
  @ExceptionHandler(IdempotencyConflictException.class)
  public Mono<ResponseEntity<OrchestrationError>> idempotencyConflict(
      IdempotencyConflictException ex) {
    log.warn("Conflicto de idempotencia: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.CONFLICT.value(), ex.getCode(), ex.getMessage())));
  }

  /** Fase del proceso incompatible con la operación pedida. */
  @ExceptionHandler(InvalidAddMediaTransition.class)
  public Mono<ResponseEntity<OrchestrationError>> invalidTransition(
      InvalidAddMediaTransition ex) {
    log.warn("Add Media transición inválida: {}", ex.getMessage());
    return Mono.just(
        ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new OrchestrationError(
                HttpStatus.CONFLICT.value(), "INVALID_ADD_MEDIA_TRANSITION", ex.getMessage())));
  }

  /** Propaga el status y el body del servicio aguas abajo (users/storage) sin degradar a 500. */
  @ExceptionHandler(WebClientResponseException.class)
  public Mono<ResponseEntity<String>> downstreamError(WebClientResponseException ex) {
    log.warn("Error {} desde {}: {}", ex.getStatusCode(), ex.getRequest().getURI(), ex.getMessage());
    String body = ex.getResponseBodyAsString();
    if (body == null || body.isBlank()) {
      body = "{\"code\":" + ex.getStatusCode().value() + ",\"error\":\"DOWNSTREAM_ERROR\"}";
    }
    MediaType contentType = ex.getHeaders().getContentType();
    if (contentType == null) {
      contentType = MediaType.APPLICATION_JSON;
    }
    return Mono.just(
        ResponseEntity.status(ex.getStatusCode()).contentType(contentType).body(body));
  }
}
