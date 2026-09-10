package com.guille.media.reproductor.uploader.storage.managedstorage.domain.model;

import java.time.Instant;
/** Durable control-plane state for a presigned multipart upload. */
public record MultipartUploadSession(
    String uploadId, String minioUploadId, String ownerUsername, String bucket,
    String objectKey, long totalBytes, String contentType, long partSizeBytes,
    int totalParts, Instant expiresAt, MultipartStatus status) {
  public MultipartUploadSession {
    if (uploadId == null || uploadId.isBlank() || minioUploadId == null || minioUploadId.isBlank()
        || ownerUsername == null || ownerUsername.isBlank() || bucket == null || bucket.isBlank()
        || objectKey == null || objectKey.isBlank() || totalBytes < 1 || contentType == null
        || contentType.isBlank() || partSizeBytes < 1 || totalParts < 1 || expiresAt == null
        || status == null) {
      throw new IllegalArgumentException("Invalid multipart upload session");
    }
  }

  public enum MultipartStatus { PENDING, COMPLETED, ABORTING, ABORTED, EXPIRED }
}
