package com.guille.media.bff.experience.activity.application.port;

import com.guille.media.bff.experience.activity.application.GetActivityFeed.ActivityEntry;
import reactor.core.publisher.Flux;

public interface ActivityProjection {
  Flux<ActivityEntry> feed(String cursor, int limit);
}
