package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.messaging;

import com.gcorp.service.app.mvflix_media_ingestion.application.CompensationRepository;
import com.gcorp.service.app.mvflix_media_ingestion.application.DownstreamClients;
import com.gcorp.service.app.mvflix_media_ingestion.application.MediaIngestionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "mvflix.compensation.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CompensationScheduler {
  private final CompensationRepository repo;
  private final MediaIngestionRepository ingestions;
  private final DownstreamClients clients;

  public CompensationScheduler(
      CompensationRepository r, MediaIngestionRepository i, DownstreamClients c) {
    repo = r;
    ingestions = i;
    clients = c;
  }

  @Scheduled(fixedDelayString = "${mvflix.compensation.poll-ms:5000}")
  public void run() {
    repo.due(20)
        .flatMap(
            c ->
                ingestions
                    .find(c.ingestionId())
                    .flatMap(
                        i -> {
                          if ("CANCEL_UPLOAD".equals(c.action()) && i.uploadId() != null) {
                            return clients
                                .storageStatus(i.uploadId(), i.actorId())
                                .flatMap(
                                    status -> {
                                      if (!"PENDING".equalsIgnoreCase(status.status()))
                                        return reactor.core.publisher.Mono.empty();
                                       return "PRESIGNED_MULTIPART".equals(i.uploadStrategy())
                                           ? clients.cancelUpload(i.uploadId(), i.actorId(),
                                               i.ingestionId() + ":compensation", i.uploadStrategy())
                                           : clients.cancelUpload(i.uploadId(), i.actorId(),
                                               i.ingestionId() + ":compensation");
                                    });
                          }
                          if ("DISCARD_DRAFT".equals(c.action())) {
                            if (i.catalogItemId() == null) {
                              return reactor.core.publisher.Mono.empty();
                            }
                            return clients
                                .catalogStatus(i.catalogItemId(), i.actorId())
                                .flatMap(
                                    status -> {
                                      if (!"DRAFT".equalsIgnoreCase(status.status())) {
                                        return reactor.core.publisher.Mono.empty();
                                      }
                                      return clients.discardDraft(
                                          i.catalogItemId(),
                                          i.actorId(),
                                          i.ingestionId() + ":discard-draft");
                                    });
                          }
                          return reactor.core.publisher.Mono.error(
                              new UnsupportedOperationException(
                                  "manual reconciliation required: " + c.action()));
                        })
                    .then(repo.success(c.id()))
                    .onErrorResume(e -> repo.failure(c.id(), e.toString())))
        .subscribe();
  }
}
