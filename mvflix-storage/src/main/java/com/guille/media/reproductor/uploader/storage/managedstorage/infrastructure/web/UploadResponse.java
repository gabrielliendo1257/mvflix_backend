package com.guille.media.reproductor.uploader.storage.managedstorage.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.ExpectedObjectData;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.StorageObject.StorageSessionStatus;

public record UploadResponse(
		@JsonProperty(value = "uploadId") String uploadId,
		@JsonProperty(value = "uploadUrl") String uploadUrl,
		@JsonProperty(value = "storageKey") String storageKey,
		@JsonProperty String method,
		@JsonProperty(value = "status") StorageSessionStatus status,
		@JsonProperty(value = "object") ExpectedObjectData objectData,
		@JsonProperty String strategy,
		@JsonProperty Long partSizeBytes,
		@JsonProperty Integer totalParts) {

}
