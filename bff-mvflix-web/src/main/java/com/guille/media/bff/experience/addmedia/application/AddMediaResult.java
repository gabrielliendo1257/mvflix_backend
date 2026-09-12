package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;

/**
 * Vista del proceso Add Media orientada a pantalla. Refleja la fase de
 * EXPERIENCIA y, cuando existen, las instrucciones de subida directa.
 */
public record AddMediaResult(
    String addMediaId,
    String ownerSubject,
    AddMediaPhase phase,
    Long movieId,
    String uploadId,
    UploadInstructions upload,
    String failureCode) {

  /** Instrucciones para que el navegador suba directo al object store. */
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

}
