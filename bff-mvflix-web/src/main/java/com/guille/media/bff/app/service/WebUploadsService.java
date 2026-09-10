package com.guille.media.bff.app.service;

import com.guille.media.bff.app.dto.StreamingSessionDto;
import com.guille.media.bff.app.dto.UploadCreateRequest;
import com.guille.media.bff.app.dto.UploadListItem;
import com.guille.media.bff.app.dto.UploadSessionDto;
import com.guille.media.bff.app.dto.UploadStatusDto;
import com.guille.media.bff.app.dto.MultipartUploadDtos;
import com.guille.media.bff.app.ports.StorageWebClient;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class WebUploadsService {

  private final StorageWebClient storageWebClient;

  public Flux<UploadListItem> list(int limit) {
    return this.storageWebClient.listUploads(limit);
  }

  public Mono<UploadSessionDto> create(UploadCreateRequest request) {
    return this.storageWebClient.createUpload(request);
  }

  public Mono<UploadStatusDto> status(Long uploadId) {
    return this.storageWebClient.uploadStatus(uploadId);
  }

  public Mono<Void> cancel(Long uploadId) {
    return this.storageWebClient.cancelUpload(uploadId);
  }

  public Mono<HttpStatus> complete(Long uploadId) {
    return this.storageWebClient.completeUpload(uploadId);
  }

  public Mono<StreamingSessionDto> stream(String objectId) {
    return this.storageWebClient.stream(objectId);
  }

  public Mono<MultipartUploadDtos.Session> createMultipart(MultipartUploadDtos.Create request) {
    return storageWebClient.createMultipart(request);
  }

  public Mono<MultipartUploadDtos.Parts> multipartParts(String uploadId, int from, int limit) {
    return storageWebClient.multipartParts(uploadId, from, limit);
  }

  public Mono<Void> completeMultipart(String uploadId, MultipartUploadDtos.Complete request) {
    return storageWebClient.completeMultipart(uploadId, request);
  }

  public Mono<Void> abortMultipart(String uploadId) {
    return storageWebClient.abortMultipart(uploadId);
  }
}
