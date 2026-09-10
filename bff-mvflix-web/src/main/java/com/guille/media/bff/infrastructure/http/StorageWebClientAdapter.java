package com.guille.media.bff.infrastructure.http;

import com.guille.media.bff.app.dto.DiscoveredFileDto;
import com.guille.media.bff.app.dto.LibraryDto;
import com.guille.media.bff.app.dto.QuotaSnapshot;
import com.guille.media.bff.app.dto.StreamingRequest;
import com.guille.media.bff.app.dto.StreamingSessionDto;
import com.guille.media.bff.app.dto.UploadCreateRequest;
import com.guille.media.bff.app.dto.UploadListItem;
import com.guille.media.bff.app.dto.UploadSessionDto;
import com.guille.media.bff.app.dto.UploadStatusDto;
import com.guille.media.bff.app.dto.MultipartUploadDtos;
import com.guille.media.bff.app.ports.StorageWebClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class StorageWebClientAdapter implements StorageWebClient {

  private static final String API = "/api/v1/movie/storage";

  private final WebClient storageWebClient;

  public StorageWebClientAdapter(@Qualifier("storageWebClient") WebClient storageWebClient) {
    this.storageWebClient = storageWebClient;
  }

  @Override
  public Mono<QuotaSnapshot> quota() {
    return this.get(API + "/quota", QuotaSnapshot.class);
  }

  @Override
  public Flux<UploadListItem> listUploads(int limit) {
    return this.storageWebClient
        .get()
        .uri(uriBuilder -> uriBuilder.path(API + "/uploads").queryParam("limit", limit).build())
                .retrieve()
        .bodyToFlux(UploadListItem.class);
  }

  @Override
  public Mono<UploadSessionDto> createUpload(UploadCreateRequest request) {
    return this.storageWebClient
        .post()
        .uri(API + "/upload")
                .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .retrieve()
        .bodyToMono(UploadSessionDto.class);
  }

  @Override
  public Mono<UploadSessionDto> findUploadByIdempotencyKey(String idempotencyKey) {
    return this.storageWebClient.get()
        .uri(API + "/uploads/by-idempotency/" + idempotencyKey)
        .retrieve()
        .bodyToMono(UploadSessionDto.class);
  }

  @Override
  public Mono<UploadStatusDto> uploadStatus(Long uploadId) {
    return this.get(API + "/upload/" + uploadId, UploadStatusDto.class);
  }

  @Override
  public Mono<UploadSessionDto> renewInstructions(Long uploadId) {
    return this.post(API + "/upload/" + uploadId + "/instructions", UploadSessionDto.class);
  }

  @Override
  public Mono<StreamingSessionDto> catalogStream(String objectId) {
    return this.postBody(
        API + "/catalog/streaming",
        new com.guille.media.bff.app.dto.StreamingRequest(objectId),
        StreamingSessionDto.class);
  }

  @Override
  public Mono<Void> cancelUpload(Long uploadId) {
    return this.postAndDiscard(API + "/upload/" + uploadId + "/cancel");
  }

  @Override
  public Mono<HttpStatus> completeUpload(Long uploadId) {
    return this.storageWebClient
        .post()
        .uri(API + "/upload/" + uploadId + "/complete")
                .retrieve()
        .toBodilessEntity()
        .map(entity -> (HttpStatus) entity.getStatusCode());
  }

  @Override
  public Mono<Void> deleteObject(Long storageId) {
    return this.storageWebClient
        .delete()
        .uri(API + "/" + storageId)
                .retrieve()
        .toBodilessEntity()
        .then();
  }

  @Override
  public Mono<StreamingSessionDto> stream(String objectId) {
    return this.storageWebClient
        .post()
        .uri(API + "/streaming")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new StreamingRequest(objectId))
        .retrieve()
        .bodyToMono(StreamingSessionDto.class);
  }

  @Override
  public Flux<LibraryDto> listLibraries() {
    return this.storageWebClient
        .get()
        .uri(API + "/libraries")
        .retrieve()
        .bodyToFlux(LibraryDto.class);
  }

  @Override
  public Mono<LibraryDto> createLibrary(String rootPath) {
    return this.storageWebClient
        .post()
        .uri(API + "/libraries")
        .bodyValue(new RegisterLibraryBody(rootPath))
        .retrieve()
        .bodyToMono(LibraryDto.class);
  }

  @Override
  public Mono<Void> deleteLibrary(Long libraryId) {
    return this.storageWebClient
        .delete()
        .uri(API + "/libraries/" + libraryId)
        .retrieve()
        .toBodilessEntity()
        .then();
  }

  @Override
  public Mono<Void> cancelLibraryScan(Long libraryId) {
    return this.storageWebClient
        .delete()
        .uri(API + "/libraries/" + libraryId + "/scan")
        .retrieve()
        .toBodilessEntity()
        .then();
  }

  private record RegisterLibraryBody(String rootPath) {}

  @Override
  public Flux<DiscoveredFileDto> listLibraryFiles(Long libraryId) {
    return this.storageWebClient
        .get()
        .uri(API + "/libraries/" + libraryId + "/files")
        .retrieve()
        .bodyToFlux(DiscoveredFileDto.class);
  }

  @Override
  public Mono<ResponseEntity<Flux<DataBuffer>>> streamLibraryFile(
      Long libraryId, String relativePath, String rangeHeader) {
    var spec =
        this.storageWebClient
            .get()
            .uri(uriBuilder -> uriBuilder
                .path(API + "/libraries/" + libraryId + "/files")
                .path("/" + relativePath)
                .build());
    if (rangeHeader != null && !rangeHeader.isBlank()) {
      spec = spec.header(HttpHeaders.RANGE, rangeHeader);
    }
    return spec
        .retrieve()
        .toEntityFlux(DataBuffer.class)
        .onErrorResume(
            org.springframework.web.reactive.function.client.WebClientResponseException.class,
            ex -> {
              HttpHeaders headers = new HttpHeaders();
              headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
              String contentRange = ex.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE);
              if (contentRange != null) {
                headers.set(HttpHeaders.CONTENT_RANGE, contentRange);
              }
              return Mono.just(ResponseEntity.status(ex.getStatusCode()).headers(headers).build());
            });
  }

  @Override
  public Mono<MultipartUploadDtos.Session> createMultipart(MultipartUploadDtos.Create request) {
    return storageWebClient.post().uri("/api/v1/uploads").contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request).retrieve().bodyToMono(MultipartUploadDtos.Session.class);
  }

  @Override
  public Mono<MultipartUploadDtos.Parts> multipartParts(String uploadId, int from, int limit) {
    return storageWebClient.get().uri(uri -> uri.path("/api/v1/uploads/{id}/parts")
        .queryParam("from", from).queryParam("limit", limit).build(uploadId))
        .retrieve().bodyToMono(MultipartUploadDtos.Parts.class);
  }

  @Override
  public Mono<Void> completeMultipart(String uploadId, MultipartUploadDtos.Complete request) {
    return storageWebClient.post().uri("/api/v1/uploads/{id}/complete", uploadId)
        .contentType(MediaType.APPLICATION_JSON).bodyValue(request).retrieve().toBodilessEntity().then();
  }

  @Override
  public Mono<Void> abortMultipart(String uploadId) {
    return storageWebClient.delete().uri("/api/v1/uploads/{id}", uploadId)
        .retrieve().toBodilessEntity().then();
  }

  private <T> Mono<T> get(String uri, Class<T> type) {
    return this.storageWebClient
        .get()
        .uri(uri)
                .retrieve()
        .bodyToMono(type);
  }


  private <T> Mono<T> post(String uri, Class<T> type) {
    return this.storageWebClient
        .post()
        .uri(uri)
        .retrieve()
        .bodyToMono(type);
  }

  private <T> Mono<T> postBody(String uri, Object body, Class<T> type) {
    var spec = this.storageWebClient.post().uri(uri)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .bodyValue(body);
    return spec.retrieve().bodyToMono(type);
  }

  private Mono<Void> postAndDiscard(String uri) {
    return this.storageWebClient
        .post()
        .uri(uri)
                .retrieve()
        .toBodilessEntity()
        .then();
  }
}
