package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.gcorp.service.app.mvflix_activity.feed.application.ProjectPlaybackActivity;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mvflix.messaging.kafka.enabled", havingValue = "true")
public class KafkaPlaybackActivityListener {
  private final ProjectPlaybackActivity projector;
  private final PlaybackActivityParser parser;
  private final MeterRegistry meters;

  public KafkaPlaybackActivityListener(ProjectPlaybackActivity projector, PlaybackActivityParser parser,
      MeterRegistry meters) {
    this.projector = projector; this.parser = parser; this.meters = meters;
  }

  @RetryableTopic(attempts = "4")
  @KafkaListener(topics = {"${mvflix.messaging.kafka.playback-started-topic:mvflix.playback-started.v1}",
      "${mvflix.messaging.kafka.playback-completed-topic:mvflix.playback-completed.v1}"},
      groupId = "${mvflix.messaging.kafka.consumer-group:mvflix-activity-feed}")
  public void onMessage(String payload) { projector.handle(parser.parse(payload)).block(); }

  @DltHandler
  public void onDlt(ConsumerRecord<?, ?> record, Exception cause) {
    meters.counter("mvflix_activity_feed_kafka_dlt_total").increment();
  }
}
