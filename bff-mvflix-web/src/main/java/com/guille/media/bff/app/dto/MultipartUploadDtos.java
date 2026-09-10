package com.guille.media.bff.app.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class MultipartUploadDtos {
  private MultipartUploadDtos() {}

  public record Create(String filename, long totalBytes, String contentType) {}
  public record Session(String uploadId, String strategy, long partSizeBytes, int totalParts,
      Instant expiresAt) {}
  public record Parts(String uploadId, List<Part> parts) {}
  public record Part(int partNumber, String url, Map<String, String> headers) {}
  public record Complete(List<CompletedPart> parts) {}
  public record CompletedPart(int partNumber, String etag) {}
}
