package com.guille.media.reproductor.uploader.storage.managedstorage.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guille.media.reproductor.uploader.advisors.GlobalExceptionHandler;
import com.guille.media.reproductor.uploader.storage.managedstorage.application.multipart.MultipartUploadService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class MultipartUploadControllerTest {
  private final MultipartUploadService service = mock(MultipartUploadService.class);
  private final WebTestClient client = WebTestClient.bindToController(new MultipartUploadController(service))
      .controllerAdvice(new GlobalExceptionHandler()).build();

  @Test
  void createsMultipartSessionWithIdempotencyKey() {
    when(service.create(any())).thenReturn(Mono.just(new MultipartUploadService.MultipartUploadResult(
        "public-id", "PRESIGNED_MULTIPART", 10, 2, Instant.now())));

    client.post().uri("/api/v1/uploads").header("Idempotency-Key", "request-1")
        .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"filename\":\"movie.mp4\",\"totalBytes\":20,\"contentType\":\"video/mp4\"}")
        .exchange().expectStatus().isOk().expectBody().jsonPath("$.uploadId").isEqualTo("public-id");

    verify(service).create(any());
  }

  @Test
  void completesWithPartNumberAndEtag() {
    when(service.complete("public-id", List.of(new MultipartUploadService.CompletedPart(1, "etag"))))
        .thenReturn(Mono.empty());

    client.post().uri("/api/v1/uploads/public-id/complete").contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"parts\":[{\"partNumber\":1,\"etag\":\"etag\"}]}")
        .exchange().expectStatus().isOk();
  }
}
