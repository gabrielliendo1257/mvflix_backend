package com.guille.media.bff.experience.addmedia.web;

import com.guille.media.bff.experience.addmedia.application.AddMediaResult;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;

/** Vista HTTP del resultado de aplicación (misma forma JSON que siempre). */
public record AddMediaResponse(
    String addMediaId,
    String ownerSubject,
    AddMediaPhase phase,
    Long movieId,
    Long uploadId,
    UploadInstructions upload,
    String failureCode) {

  public record UploadInstructions(
      String url,
      String method,
      String storageKey,
      long expectedSizeBytes,
      String expectedMimeType,
      String strategy,
      Long partSizeBytes,
      Integer totalParts) {
    public UploadInstructions(String url, String method, String storageKey, long expectedSizeBytes,
        String expectedMimeType) {
      this(url, method, storageKey, expectedSizeBytes, expectedMimeType, "SIMPLE", null, null);
    }
  }

  public static AddMediaResponse from(AddMediaResult result) {
    return new AddMediaResponse(
        result.addMediaId(),
        result.ownerSubject(),
        result.phase(),
        result.movieId(),
        result.uploadId(),
        result.upload() == null ? null : new UploadInstructions(
            result.upload().url(),
            result.upload().method(),
            result.upload().storageKey(),
            result.upload().expectedSizeBytes(),
             result.upload().expectedMimeType(), result.upload().strategy(),
             result.upload().partSizeBytes(), result.upload().totalParts()),
        result.failureCode());
  }
}
