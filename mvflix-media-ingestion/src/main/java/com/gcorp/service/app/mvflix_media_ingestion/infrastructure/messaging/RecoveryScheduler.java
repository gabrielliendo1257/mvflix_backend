package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.messaging;

import com.gcorp.service.app.mvflix_media_ingestion.application.MediaIngestionRepository;
import com.gcorp.service.app.mvflix_media_ingestion.application.RecoveryService;
import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "mvflix.recovery.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RecoveryScheduler {
  private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

  private final MediaIngestionRepository repository;
  private final RecoveryService service;

  public RecoveryScheduler(MediaIngestionRepository repository, RecoveryService service) {
    this.repository = repository;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${mvflix.recovery.poll-ms:5000}")
  public void run() {
    var claimed = repository.claimDueRecoverable(20, Duration.ofMinutes(1));
    (claimed == null ? reactor.core.publisher.Flux.<MediaIngestion>empty() : claimed)
        .flatMap(
            i -> {
              log.info(
                  "Recovery attempt started ingestionId={} phase={} version={} retryCount={} nextAttemptAt={} storageId={} storageKey={} claimLeaseSeconds={}",
                  i.ingestionId(),
                  i.phase(),
                  i.version(),
                  i.retryCount(),
                  i.nextAttemptAt(),
                  i.storageId(),
                  i.storageKey(),
                  60);
              return service
                  .recover(i)
                  .doOnSuccess(result -> log.info(
                      "Recovery attempt finished ingestionId={} phase={} version={} retryCount={} nextAttemptAt={} failureCode={} failureDetail={}",
                      result.ingestionId(),
                      result.phase(),
                      result.version(),
                      result.retryCount(),
                      result.nextAttemptAt(),
                      result.failureCode(),
                      result.failureDetail()))
                  .onErrorResume(
                      error -> {
                        log.error(
                            "Recovery attempt failed ingestionId={} phase={} version={} retryCount={} nextAttemptAt={} storageId={} storageKey={} error={}",
                            i.ingestionId(),
                            i.phase(),
                            i.version(),
                            i.retryCount(),
                            i.nextAttemptAt(),
                            i.storageId(),
                            i.storageKey(),
                            error.toString(),
                            error);
                        return service
                            .rescheduleAfterError(i, error)
                            .doOnError(rescheduleError -> log.error(
                                "Recovery reschedule failed ingestionId={} phase={} version={} retryCount={} error={}",
                                i.ingestionId(),
                                i.phase(),
                                i.version(),
                                i.retryCount(),
                                rescheduleError.toString(),
                                rescheduleError));
                      });
            })
        .onErrorResume(
            error -> {
              log.error("Recovery scheduler cycle failed error={}", error.toString(), error);
              return reactor.core.publisher.Mono.empty();
            })
        .subscribe();
  }
}
