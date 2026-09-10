package com.guille.media.bff.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient.MediaIngestionView;
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
}
