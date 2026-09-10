package com.guille.media.bff.app.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UploadSessionDto(
    String uploadId,
    String uploadUrl,
    String storageKey,
    String method,
    String status,
    ExpectedObjectData object, String strategy, Long partSizeBytes, Integer totalParts) {
  public UploadSessionDto(String uploadId, String uploadUrl, String storageKey, String method,
      String status, ExpectedObjectData object) {
    this(uploadId, uploadUrl, storageKey, method, status, object, "SIMPLE", null, null);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ExpectedObjectData(long expectedSize, String expectedMime) {}
}
