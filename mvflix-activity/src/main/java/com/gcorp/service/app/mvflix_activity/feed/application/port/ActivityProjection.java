package com.gcorp.service.app.mvflix_activity.feed.application.port;

import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityEntry;
import com.gcorp.service.app.mvflix_activity.feed.application.ProjectActivityCommand;
import com.gcorp.service.app.mvflix_activity.feed.application.CatalogItemAccessChangedCommand;
import com.gcorp.service.app.mvflix_activity.feed.application.UploadFailedCommand;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ActivityProjection {
  Mono<Void> project(ProjectActivityCommand event);
  Mono<Void> project(CatalogItemAccessChangedCommand event);
  Mono<Void> project(UploadFailedCommand event);
  Flux<ActivityEntry> feed(String audienceId, String cursor, int limit);
}
