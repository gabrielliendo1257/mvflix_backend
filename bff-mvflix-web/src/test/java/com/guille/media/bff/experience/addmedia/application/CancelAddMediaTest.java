package com.guille.media.bff.experience.addmedia.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CancelAddMediaTest {
  private final MediaIngestionClient ingestion = mock(MediaIngestionClient.class);
  private final CancelAddMedia useCase = new CancelAddMedia(ingestion);

  @Test
  void delegatesCancellationToIngestion() {
    String id = "00000000-0000-0000-0000-000000000003";
    when(ingestion.cancel("pepe", id, "corr"))
        .thenReturn(Mono.just(new MediaIngestionClient.MediaIngestionView(
            id, "pepe", 7L, "42", "CANCELLED", null, null, "pepe/video.mp4", 1024L,
            "video/mp4")));

    StepVerifier.create(useCase.handle("pepe", id, "corr"))
        .assertNext(result -> assertThat(result.phase()).isEqualTo(AddMediaPhase.CANCELLED))
        .verifyComplete();
    verify(ingestion).cancel("pepe", id, "corr");
  }
}
