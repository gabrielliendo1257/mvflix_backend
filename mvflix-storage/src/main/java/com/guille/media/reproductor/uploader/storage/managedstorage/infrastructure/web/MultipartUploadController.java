package com.guille.media.reproductor.uploader.storage.managedstorage.infrastructure.web;

import com.guille.media.reproductor.uploader.storage.managedstorage.application.multipart.MultipartUploadService;
import com.guille.media.reproductor.uploader.storage.managedstorage.application.multipart.MultipartUploadService.CompletedPart;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(value = "/api/v1/uploads", produces = MediaType.APPLICATION_JSON_VALUE)
public class MultipartUploadController {
  private final MultipartUploadService service;

  public MultipartUploadController(MultipartUploadService service) { this.service = service; }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MultipartUploadService.MultipartUploadResult>> create(
      @Valid @RequestBody CreateRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    return service.create(new MultipartUploadService.CreateMultipartUploadCommand(request.filename(),
        request.totalBytes(), request.contentType(), idempotencyKey)).map(ResponseEntity::ok);
  }

  @GetMapping("/{id}/parts")
  public Mono<MultipartUploadService.MultipartPartsResult> parts(@PathVariable String id,
      @RequestParam(defaultValue = "1") int from, @RequestParam(defaultValue = "100") int limit) {
    return service.parts(id, from, limit);
  }

  @PostMapping(value = "/{id}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<Void>> complete(@PathVariable String id,
      @Valid @RequestBody CompleteRequest request) {
    return service.complete(id, request.toParts()).thenReturn(ResponseEntity.ok().build());
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> abort(@PathVariable String id) {
    return service.abort(id).thenReturn(ResponseEntity.noContent().build());
  }

  @GetMapping("/{id}")
  public Mono<MultipartUploadService.MultipartUploadStatus> status(@PathVariable String id) {
    return service.status(id);
  }

  public record CreateRequest(@NotBlank String filename, @Min(1) long totalBytes,
      @NotBlank String contentType) {}
  public record CompleteRequest(@NotEmpty List<PartRequest> parts) {
    List<CompletedPart> toParts() { return parts.stream().map(p -> new CompletedPart(p.partNumber(), p.etag())).toList(); }
  }
  public record PartRequest(@Min(1) int partNumber, @NotBlank String etag) {}
}
