package com.guille.media.reproductor.uploader.storage.managedstorage.infrastructure.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.messages.Part;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MinioMultipartClientIntegrationTest {
  @Container
  static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z-cpuv1");

  @Test
  void completesMultipartUploadAgainstMinio() throws Exception {
    String bucket = "uploads";
    String object = "integration/multipart.bin";
    MinioClient admin = MinioClient.builder().endpoint(MINIO.getS3URL())
        .credentials(MINIO.getUserName(), MINIO.getPassword()).build();
    if (!admin.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
      admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }

    MinioMultipartClient client = new MinioMultipartClient(MinioAsyncClient.builder()
        .endpoint(MINIO.getS3URL()).credentials(MINIO.getUserName(), MINIO.getPassword()).build());
    String uploadId = client.create(bucket, object, "application/octet-stream").get().result().uploadId();
    byte[] body = new byte[5 * 1024 * 1024];
    String url = client.getPresignedObjectUrl(io.minio.GetPresignedObjectUrlArgs.builder()
        .method(io.minio.http.Method.PUT).bucket(bucket).object(object)
        .expiry(5, java.util.concurrent.TimeUnit.MINUTES)
        .extraQueryParams(Map.of("uploadId", uploadId, "partNumber", "1")).build());
    HttpResponse<Void> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30)).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
        HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isBetween(200, 299);

    client.complete(bucket, object, uploadId, new Part[] { new Part(1, response.headers().firstValue("ETag").orElseThrow()) }).get();
    assertThat(admin.statObject(io.minio.StatObjectArgs.builder().bucket(bucket).object(object).build()).size())
        .isEqualTo(body.length);
  }
}
