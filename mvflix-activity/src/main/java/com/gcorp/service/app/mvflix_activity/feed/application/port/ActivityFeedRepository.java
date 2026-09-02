package com.gcorp.service.app.mvflix_activity.feed.application.port;

import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityEntry;
import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityMutation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ActivityFeedRepository {
  Mono<Void> project(ActivityMutation mutation);
  Flux<ActivityEntry> feed(String audienceId, String cursor, int limit);
}
