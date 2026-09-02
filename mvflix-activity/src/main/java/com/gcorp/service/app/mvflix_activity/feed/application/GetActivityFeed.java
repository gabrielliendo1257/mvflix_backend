package com.gcorp.service.app.mvflix_activity.feed.application;

import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedRepository;
import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityEntry;
import reactor.core.publisher.Flux;

public class GetActivityFeed {
  private final ActivityFeedRepository projection;

  public GetActivityFeed(ActivityFeedRepository projection) {
    this.projection = projection;
  }

  public Flux<ActivityEntry> execute(String audienceId, String cursor, int limit) {
    return projection.feed(audienceId, cursor, limit);
  }
}
