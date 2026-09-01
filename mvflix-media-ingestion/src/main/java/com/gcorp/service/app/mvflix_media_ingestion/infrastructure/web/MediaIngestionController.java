package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcorp.service.app.mvflix_media_ingestion.application.MediaIngestionService;
import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@Validated
@RequestMapping(value = "/api/v1/ingestions", produces = MediaType.APPLICATION_JSON_VALUE)
public class MediaIngestionController {
  private final MediaIngestionService service;

  public MediaIngestionController(MediaIngestionService service) {
    this.service = service;
  }

  @PostMapping
  public Mono<ResponseEntity<MediaIngestion>> create(
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
      @Valid @RequestBody Create r,
      @AuthenticationPrincipal Jwt jwt) {
    if (key == null || key.isBlank() || key.length() > 128) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Idempotency-Key must be non-blank and at most 128 characters"));
    }
    return service
        .create(
             actor(jwt),
             audience(jwt),
            key,
            r.draft(),
            r.file().filename(),
            r.file().fileSize(),
            r.file().mimeType())
        .map(ResponseEntity::ok);
  }

  @GetMapping("/{id}")
  public Mono<ResponseEntity<MediaIngestion>> get(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service
        .get(id, actor(jwt))
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @PostMapping("/{id}/complete")
  public Mono<ResponseEntity<MediaIngestion>> complete(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) Complete r,
      @AuthenticationPrincipal Jwt jwt) {
    return service
        .complete(id, actor(jwt), r == null ? null : r.objectId(),
            r == null ? null : r.objectKey(), r == null ? null : r.sizeBytes())
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @PostMapping("/{id}/cancel")
  public Mono<ResponseEntity<MediaIngestion>> cancel(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service
        .cancel(id, actor(jwt))
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  private String actor(Jwt jwt) {
    if (jwt == null || jwt.getSubject() == null)
      throw new IllegalStateException("JWT subject required");
    return jwt.getSubject();
  }

  private String audience(Jwt jwt) {
    if (jwt == null || jwt.getSubject() == null)
      throw new IllegalStateException("JWT subject required");
    return jwt.getSubject();
  }

  public record File(
      @NotBlank String filename,
      @Positive @JsonProperty("file_size") long fileSize,
      @NotBlank @JsonProperty("mime_type") String mimeType) {}

  public record Create(@NotNull Map<String, Object> draft, @Valid @NotNull File file) {}

  public record Complete(
      @Positive
      @JsonProperty("object_id") Long objectId,
      @JsonProperty("object_key") String objectKey,
      @Positive
      @JsonProperty("size_bytes") Long sizeBytes) {}
}
