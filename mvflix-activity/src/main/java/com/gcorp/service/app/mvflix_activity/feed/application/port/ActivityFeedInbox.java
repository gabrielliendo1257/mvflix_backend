package com.gcorp.service.app.mvflix_activity.feed.application.port;

import reactor.core.publisher.Mono;

public interface ActivityFeedInbox {
  Mono<Void> recordReceived(String eventId, String eventType);
  Mono<Boolean> isCompleted(String eventId);
  Mono<Void> markCompleted(String eventId);
  Mono<Void> markFailed(String eventId, String eventType, String error);
}
