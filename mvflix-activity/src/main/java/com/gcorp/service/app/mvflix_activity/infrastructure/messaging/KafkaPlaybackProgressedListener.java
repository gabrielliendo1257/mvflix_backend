package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import com.gcorp.service.app.mvflix_activity.application.ActivityProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="mvflix.messaging.kafka.enabled", havingValue="true")
public class KafkaPlaybackProgressedListener {
  private final ActivityProcessor processor; private final PlaybackProgressedParser parser; private final MeterRegistry meters;
  public KafkaPlaybackProgressedListener(ActivityProcessor processor, PlaybackProgressedParser parser, MeterRegistry meters) { this.processor=processor; this.parser=parser; this.meters=meters; }
  @RetryableTopic(attempts="4", backoff=@org.springframework.retry.annotation.Backoff(delay=1000,multiplier=2.0,maxDelay=10000), topicSuffixingStrategy=TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE, retryTopicSuffix=".retry", dltTopicSuffix=".DLT")
   @KafkaListener(topics={
       "${mvflix.messaging.kafka.playback-progressed-topic:mvflix.playback-progressed.v1}",
       "${mvflix.messaging.kafka.playback-completed-topic:mvflix.playback-completed.v1}"},
       groupId="${mvflix.messaging.kafka.consumer-group:mvflix-activity}")
  public void onMessage(String payload) { processor.process(parser.parse(payload)).block(); }
  @DltHandler public void onDlt(ConsumerRecord<?,?> record, Exception cause) { meters.counter("mvflix_activity_kafka_dlt_total").increment(); }
}
