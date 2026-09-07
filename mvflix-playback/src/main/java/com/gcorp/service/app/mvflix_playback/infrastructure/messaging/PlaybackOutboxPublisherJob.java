package com.gcorp.service.app.mvflix_playback.infrastructure.messaging;

import com.gcorp.service.app.mvflix_playback.application.PlaybackOutbox;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mvflix.messaging.kafka.enabled", havingValue = "true")
public class PlaybackOutboxPublisherJob {
  private final PlaybackOutbox outbox;
  private final KafkaPlaybackOutboxPublisher publisher;
  private final int batchSize;
  private final int maxAttempts;
  private final Duration lease;
  private final Duration retryDelay;

  public PlaybackOutboxPublisherJob(PlaybackOutbox outbox, KafkaPlaybackOutboxPublisher publisher,
      @Value("${mvflix.outbox.batch-size:25}") int batchSize,
      @Value("${mvflix.outbox.max-attempts:10}") int maxAttempts,
      @Value("${mvflix.outbox.lease:PT2M}") Duration lease,
      @Value("${mvflix.outbox.retry-delay:PT1M}") Duration retryDelay) {
    this.outbox = outbox;
    this.publisher = publisher;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.lease = lease;
    this.retryDelay = retryDelay;
  }

  @Scheduled(fixedDelayString = "${mvflix.outbox.poll-interval:PT5S}")
  public void publishPending() {
    outbox.claim(batchSize, maxAttempts, lease)
        .flatMap(message -> publisher.publish(message)
            .then(outbox.markPublished(message.eventId()))
            .onErrorResume(error -> outbox.markFailed(message.eventId(), error.getMessage(), retryDelay)))
        .then().subscribe(null, ignored -> { });
  }
}
