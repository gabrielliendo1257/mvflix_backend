package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.gcorp.service.app.mvflix_activity.feed.application.ProjectActivityEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mvflix.messaging.kafka.enabled", havingValue = "true")
public class KafkaMediaIngestionActivityListener {
  private final ProjectActivityEvent projector;
  private final MediaIngestionActivityParser parser;
  private final MeterRegistry meters;

  public KafkaMediaIngestionActivityListener(ProjectActivityEvent projector,
      MediaIngestionActivityParser parser, MeterRegistry meters) {
    this.projector = projector;
    this.parser = parser;
    this.meters = meters;
  }

  @RetryableTopic(attempts = "4",
      backoff = @org.springframework.retry.annotation.Backoff(delay = 1000, multiplier = 2.0,
          maxDelay = 10000),
      topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
      retryTopicSuffix = ".retry", dltTopicSuffix = ".DLT")
  @KafkaListener(
      topics = "#{'${mvflix.messaging.kafka.media-ingestion-topics:mvflix.media-ingestion-started.v1,mvflix.media-ingestion-completed.v1,mvflix.media-ingestion-failed.v1,mvflix.media-ingestion-cancelled.v1}'.split(',')}",
      groupId = "${mvflix.messaging.kafka.feed-consumer-group:mvflix-activity-feed}")
  public void onMessage(String payload) {
    projector.handle(parser.parse(payload)).block();
  }

  @DltHandler
  public void onDlt(ConsumerRecord<?, ?> record, Exception cause) {
    meters.counter("mvflix_activity_media_ingestion_kafka_dlt_total").increment();
  }
}
