package com.guille.media.bff.experience.playback.web;

import com.guille.media.bff.experience.playback.application.LocalStreamTokenException;
import com.guille.media.bff.experience.playback.application.StartPlayback;
import com.guille.media.bff.infrastructure.security.AnonymousViewerIdentity;
import com.guille.media.bff.experience.playback.application.port.LocalPlaybackAccess;
import com.guille.media.bff.experience.playback.application.port.PlaybackService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Experiencia Playback: el front dice "reproduce esta media" y recibe la
 * sesión lista para el player. Para MANAGED la URL apunta al object store
 * (Range nativo); para LOCAL el BFF sirve los bytes mediante un cliente M2M
 * con scope {@code storage.stream}, autorizado por capability HMAC.
 */
@Tag(name = "Web · Playback", description = "Sesión de reproducción y entrega LOCAL con Range")
@RestController
@RequestMapping("/web/playback")
public class PlaybackController {

  private final StartPlayback startPlayback;
  private final LocalPlaybackAccess localAccess;
  private final PlaybackService playbackService;
  private final AnonymousViewerIdentity viewerIdentity;
  private final WebClient playbackStorageClient;

  public PlaybackController(
      StartPlayback startPlayback,
      LocalPlaybackAccess localAccess,
       PlaybackService playbackService,
       AnonymousViewerIdentity viewerIdentity,
       @Qualifier("playbackWebClient") WebClient playbackStorageClient) {
    this.startPlayback = startPlayback;
    this.localAccess = localAccess;
    this.playbackService = playbackService;
    this.viewerIdentity = viewerIdentity;
    this.playbackStorageClient = playbackStorageClient;
  }

  @PostMapping(value = "/sessions/{sessionId}/progress", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ProgressResponse> progress(
      @PathVariable String sessionId, @RequestBody ProgressRequest request,
      org.springframework.web.server.ServerWebExchange exchange) {
    return this.viewerIdentity.resolve(exchange).flatMap(viewerId -> playbackService.progress(sessionId, viewerId,
            new PlaybackService.ProgressCommand(request.sequence(), request.positionSeconds(),
                request.durationSeconds(), request.completed())))
        .map(result -> new ProgressResponse(result.sequence(), result.positionSeconds(), result.status()));
  }

  public record ProgressRequest(long sequence, long positionSeconds, Long durationSeconds,
      boolean completed) {}

  public record ProgressResponse(long sequence, Long positionSeconds, String status) {}

  @Operation(summary = "Inicia la reproducción: autoriza, resuelve contenido y compone la sesión")
  @PostMapping(value = "/{mediaId}/session", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<StartPlaybackResponse>> start(
      @PathVariable long mediaId, org.springframework.web.server.ServerWebExchange exchange) {
    return this.viewerIdentity.resolve(exchange)
        .flatMap(subject -> this.startPlayback.handle(subject, mediaId))
        .map(result -> ResponseEntity.status(HttpStatus.CREATED)
            .body(StartPlaybackResponse.from(result)));
  }

  /**
   * Entrega LOCAL con soporte Range (206). Público por diseño: la autenticación
   * viaja en la capability del query param porque un {@code <video>} cross-origin
   * no envía la cookie de sesión. Las credenciales hacia storage se resuelven
   * desde la sesión OAuth2 viva del sujeto de la capability.
   */
  @Operation(summary = "Bytes de un asset LOCAL (Range); requiere capability emitida por /session")
  @GetMapping("/assets/{assetId}/stream")
  public Mono<ResponseEntity<Flux<DataBuffer>>> stream(
      @PathVariable long assetId,
      @RequestParam(name = "token", required = false) String token,
      ServerHttpRequest request) {
    return this.localAccess
        .resolve(token)
        .flatMap(grant -> {
          if (grant.assetId() != assetId) {
            return Mono.error(new LocalStreamTokenException("Capability de otro asset"));
          }
          return this.deliver(grant, request.getHeaders().getFirst(HttpHeaders.RANGE));
        });
  }

  /**
   * El cliente outbound usa client credentials; la capability solo identifica
   * el asset autorizado y nunca transporta credenciales OAuth2.
   */
  private Mono<ResponseEntity<Flux<DataBuffer>>> deliver(
      LocalPlaybackAccess.LocalGrant grant, String rangeHeader) {
    var request = this.playbackStorageClient.get()
        .uri(uriBuilder -> uriBuilder.path("/api/v1/movie/storage/playback/libraries/{libraryId}/files/{path}")
            .build(grant.libraryId(), grant.relativePath()));
    if (rangeHeader != null && !rangeHeader.isBlank()) {
      request = request.header(HttpHeaders.RANGE, rangeHeader);
    }
    return request.retrieve().toEntityFlux(DataBuffer.class);
  }

}
