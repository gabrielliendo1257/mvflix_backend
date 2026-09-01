package com.gcorp.service.app.mvflix_activity.feed.application.port;

import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityEntry;
import com.gcorp.service.app.mvflix_activity.feed.application.ProjectActivityCommand;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ActivityProjection {
  Mono<Void> project(ProjectActivityCommand event);
  Flux<ActivityEntry> feed(String audienceId, String cursor, int limit);
}
