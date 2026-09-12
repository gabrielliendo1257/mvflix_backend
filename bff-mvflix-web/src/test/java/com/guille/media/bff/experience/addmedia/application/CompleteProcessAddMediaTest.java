package com.guille.media.bff.experience.addmedia.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guille.media.bff.app.dto.MultipartUploadDtos;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CompleteProcessAddMediaTest {
  private final MediaIngestionClient ingestion = mock(MediaIngestionClient.class);
  private final CompleteProcessAddMedia useCase = new CompleteProcessAddMedia(ingestion);

  @Test
  void delegatesSimpleCompletionToIngestion() {
    String id = "00000000-0000-0000-0000-000000000001";
    when(ingestion.complete("pepe", id, 1024L, "corr"))
        .thenReturn(Mono.just(view(id, "COMPLETED", "SIMPLE")));

    StepVerifier.create(useCase.handle("pepe", id, 1024L, "corr"))
        .assertNext(result -> assertThat(result.phase()).isEqualTo(AddMediaPhase.READY))
        .verifyComplete();
    verify(ingestion).complete("pepe", id, 1024L, "corr");
  }

  @Test
  void delegatesMultipartPartsToIngestion() {
    String id = "00000000-0000-0000-0000-000000000002";
    var parts = List.of(new MultipartUploadDtos.CompletedPart(1, "etag-1"));
    when(ingestion.status("pepe", id, "corr"))
        .thenReturn(Mono.just(view(id, "AWAITING_UPLOAD", "PRESIGNED_MULTIPART")));
    when(ingestion.complete("pepe", id, 1024L, "corr", parts))
        .thenReturn(Mono.just(view(id, "COMPLETED", "PRESIGNED_MULTIPART")));

    StepVerifier.create(useCase.handleMultipart("pepe", id, 1024L, parts, "corr"))
        .assertNext(result -> assertThat(result.phase()).isEqualTo(AddMediaPhase.READY))
        .verifyComplete();
    verify(ingestion).complete("pepe", id, 1024L, "corr", parts);
  }

  private static MediaIngestionClient.MediaIngestionView view(
      String id, String phase, String strategy) {
    return new MediaIngestionClient.MediaIngestionView(
        id, "pepe", 7L, "42", phase, null, null, "pepe/video.mp4", 1024L, "video/mp4",
        strategy, 512L, 2);
  }
}
