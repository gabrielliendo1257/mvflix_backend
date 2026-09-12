package com.guille.media.bff.experience.addmedia.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient.MediaIngestionView;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.Test;

class MediaIngestionRoutingTest {
  @Test
  void enabledStatusUsesIngestionWhenThereIsNoLegacyRow() {
    var ingestion = mock(MediaIngestionClient.class);
    when(ingestion.status("pepe", "11111111-1111-1111-1111-111111111111", "corr"))
        .thenReturn(Mono.just(new MediaIngestionView("11111111-1111-1111-1111-111111111111", "pepe", 7L,
            "42", "AWAITING_UPLOAD", null, "http://put", "videos/a.mp4", 10, "video/mp4")));

    var result = new GetAddMediaStatus(ingestion)
        .handle("pepe", "11111111-1111-1111-1111-111111111111", "corr").block();

    assertThat(result.phase()).isEqualTo(com.guille.media.bff.experience.addmedia.model.AddMediaPhase.WAITING_FOR_UPLOAD);
    verify(ingestion).status("pepe", "11111111-1111-1111-1111-111111111111", "corr");
  }
}
