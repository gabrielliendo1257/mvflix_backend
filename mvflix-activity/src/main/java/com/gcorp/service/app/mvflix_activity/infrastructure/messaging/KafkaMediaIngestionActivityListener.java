package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.gcorp.service.app.mvflix_activity.feed.application.ProjectActivityEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mvflix.messaging.kafka.enabled", havingValue = "true")
public class KafkaMediaIngestionActivityListener {
  private final ProjectActivityEvent projector;
  private final MediaIngestionActivityParser parser;

  public KafkaMediaIngestionActivityListener(ProjectActivityEvent projector,
      MediaIngestionActivityParser parser) {
    this.projector = projector;
    this.parser = parser;
  }

  @KafkaListener(
      topics = "${mvflix.messaging.kafka.media-ingestion-topics:mvflix.media-ingestion-started.v1,mvflix.media-ingestion-completed.v1,mvflix.media-ingestion-failed.v1,mvflix.media-ingestion-cancelled.v1}",
      groupId = "${mvflix.messaging.kafka.feed-consumer-group:mvflix-activity-feed}")
  public void onMessage(String payload) {
    projector.handle(parser.parse(payload)).block();
  }
}
