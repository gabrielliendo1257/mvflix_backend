package com.guille.media.bff.experience.activity.application.port;

import com.guille.media.bff.experience.activity.application.GetActivityFeed.ActivityPage;
import reactor.core.publisher.Mono;

public interface ActivityProjection {
  Mono<ActivityPage> feed(String cursor, int limit);
}
