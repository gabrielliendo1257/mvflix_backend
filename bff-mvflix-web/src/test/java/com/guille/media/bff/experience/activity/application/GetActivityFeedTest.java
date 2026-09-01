package com.guille.media.bff.experience.activity.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guille.media.bff.experience.activity.application.port.ActivityProjection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetActivityFeedTest {
  private final ActivityProjection projection = mock(ActivityProjection.class);
  private final GetActivityFeed useCase = new GetActivityFeed(projection);

  @Test
  void normalizesCursorAndCapsLimit() {
    var entry = new GetActivityFeed.ActivityEntry(UUID.randomUUID(), UUID.randomUUID(),
        "MEDIA_INGESTION", "COMPLETED", null, null, null, null, null, "next");
    when(projection.feed("abc", 100)).thenReturn(Mono.just(new GetActivityFeed.ActivityPage(
        java.util.List.of(entry), null, false)));

    StepVerifier.create(useCase.execute("  abc ", 500))
        .assertNext(page -> org.assertj.core.api.Assertions.assertThat(page.items())
            .containsExactly(entry))
        .verifyComplete();

    verify(projection).feed("abc", 100);
  }

  @Test
  void usesDefaultLimitAndNoCursorWhenNotProvided() {
    when(projection.feed(null, 20)).thenReturn(Mono.just(new GetActivityFeed.ActivityPage(
        java.util.List.of(), null, false)));

    StepVerifier.create(useCase.execute("   ", null))
        .assertNext(page -> org.assertj.core.api.Assertions.assertThat(page.items()).isEmpty())
        .verifyComplete();

    verify(projection).feed(null, 20);
  }
}
