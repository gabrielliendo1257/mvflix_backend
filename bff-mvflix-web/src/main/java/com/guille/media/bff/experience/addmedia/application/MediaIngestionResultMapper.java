package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient.MediaIngestionView;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;

public final class MediaIngestionResultMapper {
  private MediaIngestionResultMapper() {}

  public static AddMediaResult map(MediaIngestionView view) {
    AddMediaPhase phase = switch (view.phase()) {
      case "AWAITING_UPLOAD" -> AddMediaPhase.WAITING_FOR_UPLOAD;
      case "COMPLETED" -> AddMediaPhase.READY;
      case "PREPARING_CATALOG", "PREPARING_UPLOAD" -> AddMediaPhase.PREPARING;
      case "FINALIZING_CATALOG" -> AddMediaPhase.FINALIZING;
      case "RECONCILIATION_REQUIRED" -> AddMediaPhase.FAILED;
      default -> AddMediaPhase.valueOf(view.phase());
    };
    AddMediaResult.UploadInstructions upload = phase == AddMediaPhase.WAITING_FOR_UPLOAD
        && (view.uploadUrl() != null || "PRESIGNED_MULTIPART".equals(view.strategy()))
        ? new AddMediaResult.UploadInstructions(view.uploadUrl(),
            "PRESIGNED_MULTIPART".equals(view.strategy()) ? null : "PUT", view.storageKey(),
            view.fileSize(), view.mimeType(), view.strategy(), view.partSizeBytes(), view.totalParts()) : null;
    return new AddMediaResult(view.ingestionId(), view.actorId(), phase, view.catalogItemId(), view.uploadId(),
        upload, view.failureCode());
  }
}
