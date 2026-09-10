package com.guille.media.bff.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient.MediaIngestionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MediaIngestionResultMapperTest {
  @Test
  void adapterContractMapsPublicPhaseAndPresignedInstructions() {
    var view = new MediaIngestionView("id", "actor", 7L, "42", "AWAITING_UPLOAD", null,
        "http://put", "key", 12, "video/mp4");
    var result = com.guille.media.bff.experience.addmedia.application.MediaIngestionResultMapper.map(view);
    assertThat(result.phase().name()).isEqualTo("WAITING_FOR_UPLOAD");
    assertThat(result.upload().url()).isEqualTo("http://put");
  }

  @Test
  void exposesCatalogFinalizationAsFinalizing() {
    var view = new MediaIngestionView("id", "actor", 7L, "42", "FINALIZING_CATALOG", null,
        null, "key", 12, "video/mp4");

    var result = com.guille.media.bff.experience.addmedia.application.MediaIngestionResultMapper.map(view);

    assertThat(result.phase().name()).isEqualTo("FINALIZING");
  }

  @Test
  void mapsMultipartInstructionsWithoutTurningThemIntoSimplePut() {
    var view = new MediaIngestionView("id", "actor", 7L, "public-upload", "AWAITING_UPLOAD", null,
        null, "actor/videos/public-upload-file.mp4", 256L * 1024 * 1024 + 1, "video/mp4",
        "PRESIGNED_MULTIPART", 10L * 1024 * 1024, 26);

    var upload = com.guille.media.bff.experience.addmedia.application.MediaIngestionResultMapper
        .map(view).upload();

    assertThat(upload.strategy()).isEqualTo("PRESIGNED_MULTIPART");
    assertThat(upload.method()).isNull();
    assertThat(upload.partSizeBytes()).isEqualTo(10L * 1024 * 1024);
    assertThat(upload.totalParts()).isEqualTo(26);
  }

  @Test
  void preservesOpaqueMultipartIdAndReadsUploadStrategyFromIngestionJson() throws Exception {
    var wire = new ObjectMapper().readValue("""
        {"uploadId":"ef185c67-2398-4a2d-b750-87e47feff3dc",
         "uploadStrategy":"PRESIGNED_MULTIPART","phase":"AWAITING_UPLOAD",
         "storageKey":"owner/video","fileSize":10,"mimeType":"video/mp4",
         "partSizeBytes":10485760,"totalParts":1}
        """, MediaIngestionWebClientAdapter.MediaIngestionWire.class);

    var view = new MediaIngestionView(wire.ingestionId, wire.actorId,
        wire.catalogItemId, wire.uploadId, wire.phase, wire.failureCode, wire.uploadUrl,
        wire.storageKey, wire.fileSize, wire.mimeType, wire.strategy, wire.partSizeBytes,
        wire.totalParts);
    var result = com.guille.media.bff.experience.addmedia.application.MediaIngestionResultMapper.map(view);

    assertThat(result.uploadId()).isEqualTo("ef185c67-2398-4a2d-b750-87e47feff3dc");
    assertThat(result.upload().strategy()).isEqualTo("PRESIGNED_MULTIPART");
  }
}
