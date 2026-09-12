package com.guille.media.bff.experience.addmedia.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guille.media.bff.app.dto.UserProfile;
import com.guille.media.bff.app.ports.UsersWebPort;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import com.guille.media.bff.experience.addmedia.model.AddMediaPhase;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class StartAddMediaTest {
  private final UsersWebPort users = mock(UsersWebPort.class);
  private final MediaIngestionClient ingestion = mock(MediaIngestionClient.class);
  private final StartAddMedia useCase = new StartAddMedia(users, ingestion);

  @Test
  void delegatesStartToIngestionAfterUserGate() {
    var command = new StartAddMediaCommand(null, null, null, null);
    when(users.me()).thenReturn(Mono.just(new UserProfile(
        "u1", "pepe", null, null, "pepe@example.com", "FREE", true, 0, false)));
    when(ingestion.create("pepe", command, "corr"))
        .thenReturn(Mono.just(new MediaIngestionClient.MediaIngestionView(
            "id", "pepe", 7L, "42", "AWAITING_UPLOAD", null, "url", "key", 1024L,
            "video/mp4")));

    StepVerifier.create(useCase.handle("pepe", command, "corr"))
        .assertNext(result -> assertThat(result.phase()).isEqualTo(AddMediaPhase.WAITING_FOR_UPLOAD))
        .verifyComplete();
    verify(ingestion).create("pepe", command, "corr");
  }
}
