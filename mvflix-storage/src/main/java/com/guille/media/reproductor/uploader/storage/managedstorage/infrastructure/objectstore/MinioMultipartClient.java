package com.guille.media.reproductor.uploader.storage.managedstorage.infrastructure.objectstore;

import io.minio.AbortMultipartUploadResponse;
import io.minio.CreateMultipartUploadResponse;
import io.minio.MinioAsyncClient;
import io.minio.ObjectWriteResponse;
import io.minio.messages.Part;
import java.util.concurrent.CompletableFuture;

/** Narrow public facade over the protected multipart operations in MinIO 8.5.7. */
public class MinioMultipartClient extends MinioAsyncClient {
  public MinioMultipartClient(MinioAsyncClient delegate) {
    super(delegate);
  }

  public CompletableFuture<CreateMultipartUploadResponse> create(String bucket, String object,
      String contentType) throws Exception {
    return createMultipartUploadAsync(bucket, null, object, null, null);
  }

  public CompletableFuture<ObjectWriteResponse> complete(String bucket, String object,
      String uploadId, Part[] parts) throws Exception {
    return completeMultipartUploadAsync(bucket, null, object, uploadId, parts, null, null);
  }

  public CompletableFuture<AbortMultipartUploadResponse> abort(String bucket, String object,
      String uploadId) throws Exception {
    return abortMultipartUploadAsync(bucket, null, object, uploadId, null, null);
  }
}
