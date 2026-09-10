package com.guille.media.reproductor.uploader.storage.managedstorage.application.multipart;

import java.util.List;
import reactor.core.publisher.Mono;

public interface MultipartUploadService {
  Mono<MultipartUploadResult> create(CreateMultipartUploadCommand command);
  Mono<MultipartPartsResult> parts(String uploadId, int from, int limit);
  Mono<Void> complete(String uploadId, List<CompletedPart> parts);
  Mono<Void> abort(String uploadId);

  record CreateMultipartUploadCommand(String filename, long totalBytes, String contentType,
      String idempotencyKey) {}
  record CompletedPart(int partNumber, String etag) {}
  record MultipartUploadResult(String uploadId, String strategy, long partSizeBytes,
      int totalParts, java.time.Instant expiresAt) {}
  record MultipartPartsResult(String uploadId, List<PartInstruction> parts) {}
  record PartInstruction(int partNumber, String url, java.util.Map<String, String> headers) {}
}
