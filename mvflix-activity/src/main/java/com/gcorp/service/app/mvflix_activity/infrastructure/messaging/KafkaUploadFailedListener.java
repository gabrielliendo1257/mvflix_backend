package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.gcorp.service.app.mvflix_activity.feed.application.ProjectUploadFailed;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mvflix.messaging.kafka.enabled", havingValue = "true")
public class KafkaUploadFailedListener {
  private final ProjectUploadFailed projector;
  private final UploadFailedParser parser;
  private final MeterRegistry meters;

  public KafkaUploadFailedListener(ProjectUploadFailed projector, UploadFailedParser parser,
      MeterRegistry meters) {
    this.projector = projector;
    this.parser = parser;
    this.meters = meters;
  }

  @RetryableTopic(attempts = "4",
      backoff = @org.springframework.retry.annotation.Backoff(delay = 1000, multiplier = 2.0,
          maxDelay = 10000), topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
      retryTopicSuffix = ".retry", dltTopicSuffix = ".DLT")
  @KafkaListener(topics = "${mvflix.messaging.kafka.upload-failed-topic:mvflix.upload-failed.v1}",
      groupId = "${mvflix.messaging.kafka.upload-failed-consumer-group:mvflix-activity-upload-failed}")
  public void onMessage(String payload) {
    projector.handle(parser.parse(payload)).block();
  }

  @DltHandler
  public void onDlt(ConsumerRecord<?, ?> record, Exception cause) {
    meters.counter("mvflix_activity_upload_failed_kafka_dlt_total").increment();
  }
}
