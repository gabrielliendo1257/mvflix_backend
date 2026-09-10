package com.guille.media.reproductor.uploader.storage.managedstorage.infrastructure.objectstore;

import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.MultipartObjectStorageService;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioAsyncClient;
import io.minio.http.Method;
import io.minio.messages.Part;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class MinioMultipartStorage implements MultipartObjectStorageService {
  private final MinioMultipartClient client;

  public MinioMultipartStorage(MinioMultipartClient client) {
    this.client = client;
  }

  @Override
  public Mono<String> create(String bucket, String objectKey, String contentType) {
    return Mono.fromCallable(() -> client.create(bucket, objectKey, contentType))
        .flatMap(Mono::fromFuture).map(response -> response.result().uploadId());
  }

  @Override
  public Mono<String> presignedPart(String bucket, String objectKey, UUID minioUploadId,
      int partNumber, Duration expiry) {
    return Mono.fromCallable(() -> client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
        .method(Method.PUT).bucket(bucket).object(objectKey)
        .expiry((int) expiry.toSeconds(), TimeUnit.SECONDS)
        .extraQueryParams(Map.of("uploadId", minioUploadId.toString(),
            "partNumber", Integer.toString(partNumber))).build()));
  }

  @Override
  public Mono<Void> complete(String bucket, String objectKey, UUID minioUploadId,
      List<MultipartPart> parts) {
    Part[] minioParts = parts.stream().map(p -> new Part(p.partNumber(), p.etag())).toArray(Part[]::new);
    return Mono.fromCallable(() -> client.complete(bucket, objectKey, minioUploadId.toString(), minioParts))
        .flatMap(Mono::fromFuture).then();
  }

  @Override
  public Mono<Void> abort(String bucket, String objectKey, UUID minioUploadId) {
    return Mono.fromCallable(() -> client.abort(bucket, objectKey, minioUploadId.toString()))
        .flatMap(Mono::fromFuture).then();
  }

  @org.springframework.context.annotation.Configuration
  static class MultipartMinioConfiguration {
    @Bean
    MinioMultipartClient minioMultipartClient(MinioAsyncClient client) {
      return new MinioMultipartClient(client);
    }
  }
}
