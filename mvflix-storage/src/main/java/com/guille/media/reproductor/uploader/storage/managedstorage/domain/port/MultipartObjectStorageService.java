package com.guille.media.reproductor.uploader.storage.managedstorage.domain.port;

import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

public interface MultipartObjectStorageService {
  Mono<String> create(String bucket, String objectKey, String contentType);
  Mono<String> presignedPart(String bucket, String objectKey, String minioUploadId,
      int partNumber, Duration expiry);
  Mono<Void> complete(String bucket, String objectKey, String minioUploadId,
      List<MultipartPart> parts);
  Mono<Void> abort(String bucket, String objectKey, String minioUploadId);

  record MultipartPart(int partNumber, String etag) {}
}
