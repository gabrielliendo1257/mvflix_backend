package com.guille.media.bff.experience.activity.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guille.media.bff.experience.activity.application.port.ActivityProjection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class GetActivityFeedTest {
  private final ActivityProjection projection = mock(ActivityProjection.class);
  private final GetActivityFeed useCase = new GetActivityFeed(projection);

  @Test
  void normalizesCursorAndCapsLimit() {
    var entry = new GetActivityFeed.ActivityEntry(UUID.randomUUID(), UUID.randomUUID(),
        "MEDIA_INGESTION", "COMPLETED", null, null, null, null, null, "next");
    when(projection.feed("abc", 100)).thenReturn(Flux.just(entry));

    StepVerifier.create(useCase.execute("  abc ", 500))
        .expectNext(entry)
        .verifyComplete();

    verify(projection).feed("abc", 100);
  }

  @Test
  void usesDefaultLimitAndNoCursorWhenNotProvided() {
    when(projection.feed(null, 20)).thenReturn(Flux.empty());

    StepVerifier.create(useCase.execute("   ", null)).verifyComplete();

    verify(projection).feed(null, 20);
  }
}
