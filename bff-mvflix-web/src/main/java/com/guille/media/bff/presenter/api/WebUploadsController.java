package com.guille.media.bff.presenter.api;

import com.guille.media.bff.app.dto.StreamingRequest;
import com.guille.media.bff.app.dto.StreamingSessionDto;
import com.guille.media.bff.app.dto.UploadCreateRequest;
import com.guille.media.bff.app.dto.UploadListItem;
import com.guille.media.bff.app.dto.UploadSessionDto;
import com.guille.media.bff.app.dto.UploadStatusDto;
import com.guille.media.bff.app.dto.MultipartUploadDtos;
import com.guille.media.bff.app.service.WebUploadsService;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag(name = "Web · Uploads", description = "Sesiones de upload (listado/status legacy + streaming preview)")
@RestController
@RequestMapping("/web/uploads")
public class WebUploadsController {

  private final WebUploadsService webUploadsService;

  public WebUploadsController(WebUploadsService webUploadsService) {
    this.webUploadsService = webUploadsService;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public Flux<UploadListItem> list(@RequestParam(defaultValue = "20") int limit) {
    return this.webUploadsService.list(limit);
  }

  /**
   * @deprecated Paso técnico de la coreografía antigua. Usar
   *             {@code POST /web/add-media}, que devuelve las instrucciones de
   *             subida junto al proceso completo.
   */
  @Deprecated
  @PostMapping(
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<UploadSessionDto>> create(@RequestBody UploadCreateRequest request) {
    return this.webUploadsService.create(request).map(ResponseEntity::ok);
  }

  @GetMapping(value = "/{uploadId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<UploadStatusDto>> status(@PathVariable Long uploadId) {
    return this.webUploadsService.status(uploadId).map(ResponseEntity::ok);
  }

  @PostMapping("/{uploadId}/cancel")
  public Mono<ResponseEntity<Void>> cancel(@PathVariable Long uploadId) {
    return this.webUploadsService.cancel(uploadId).thenReturn(ResponseEntity.ok().build());
  }

  /**
   * @deprecated Finalización de sesión aislada. Usar
   *             {@code POST /web/add-media/{addMediaId}/complete}, que además
   *             verifica y persiste la película.
   */
  @Deprecated
  @PostMapping("/{uploadId}/complete")
  public Mono<ResponseEntity<Void>> complete(@PathVariable Long uploadId) {
    return this.webUploadsService
        .complete(uploadId)
        .map(status -> ResponseEntity.status(status).build());
  }

  @PostMapping(
      value = "/streaming",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<StreamingSessionDto>> stream(@RequestBody StreamingRequest request) {
    return this.webUploadsService.stream(request.objectId()).map(ResponseEntity::ok);
  }

  @PostMapping(value = "/multipart", produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MultipartUploadDtos.Session>> createMultipart(
      @RequestBody MultipartUploadDtos.Create request) {
    return webUploadsService.createMultipart(request).map(ResponseEntity::ok);
  }

  @GetMapping(value = "/multipart/{uploadId}/parts", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MultipartUploadDtos.Parts>> multipartParts(
      @PathVariable String uploadId, @RequestParam(defaultValue = "1") int from,
      @RequestParam(defaultValue = "20") int limit) {
    return webUploadsService.multipartParts(uploadId, from, limit).map(ResponseEntity::ok);
  }

  @PostMapping(value = "/multipart/{uploadId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<Void>> completeMultipart(@PathVariable String uploadId,
      @RequestBody MultipartUploadDtos.Complete request) {
    return webUploadsService.completeMultipart(uploadId, request)
        .thenReturn(ResponseEntity.ok().build());
  }

  @DeleteMapping("/multipart/{uploadId}")
  public Mono<ResponseEntity<Void>> abortMultipart(@PathVariable String uploadId) {
    return webUploadsService.abortMultipart(uploadId)
        .thenReturn(ResponseEntity.noContent().build());
  }
}
