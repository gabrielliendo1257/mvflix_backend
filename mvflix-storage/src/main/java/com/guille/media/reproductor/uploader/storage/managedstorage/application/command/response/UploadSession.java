package com.guille.media.reproductor.uploader.storage.managedstorage.application.command.response;

import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.ExpectedObjectData;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageObject.StorageSessionStatus;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageKey;
import java.time.Instant;

public record UploadSession(
    String uploadId,
    String uploadUrl,
    StorageKey storageKey,
    String method,
    Instant expiresAt,
    StorageSessionStatus currentStatus,
    ExpectedObjectData objectData,
    String strategy,
    Long partSizeBytes,
    Integer totalParts) {
  public UploadSession(String uploadId, String uploadUrl, StorageKey storageKey, String method,
      Instant expiresAt, StorageSessionStatus currentStatus, ExpectedObjectData objectData) {
    this(uploadId, uploadUrl, storageKey, method, expiresAt, currentStatus, objectData,
        "SIMPLE", null, null);
  }
}
