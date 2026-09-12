package com.guille.media.bff.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.guille.media.bff.experience.addmedia.application.StartAddMediaCommand;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient.MediaIngestionView;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import java.util.List;
import com.guille.media.bff.app.dto.MultipartUploadDtos.CompletedPart;

@Component
@Slf4j
public class MediaIngestionWebClientAdapter implements MediaIngestionClient {
  private static final String API = "/api/v1/ingestions";
  private final WebClient client;

  public MediaIngestionWebClientAdapter(@Qualifier("mediaIngestionWebClient") WebClient client) {
    this.client = client;
  }

  @Override
  public Mono<MediaIngestionView> create(String ownerSubject, StartAddMediaCommand command,
      String correlationId) {
    var draft = new java.util.HashMap<String, Object>();
    draft.put("draft", command.movie().draft());
    if (command.movie().providerId() != null) draft.put("tmdb_id", command.movie().providerId());
    var access = command.access();
    if (access != null && access.visibility() != null) draft.put("visibility", access.visibility());
    if (access != null && access.sharedWith() != null && !access.sharedWith().isEmpty()) {
      draft.put("sharedWith", access.sharedWith());
    }
    var payload = Map.<String, Object>of("draft", draft,
        "file", new CreateFile(command.file().filename(), command.file().sizeBytes(), command.file().mimeType()));
    return this.client.post().uri(API).contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", command.idempotencyKey()).header("X-Correlation-ID", correlationId)
        .bodyValue(payload).retrieve().bodyToMono(MediaIngestionWire.class).map(MediaIngestionWebClientAdapter::view)
        .transform(this::translate);
  }

  @Override
  public Mono<MediaIngestionView> status(String ownerSubject, String id, String correlationId) {
    return this.client.get().uri(API + "/{id}", UUID.fromString(id)).header("X-Correlation-ID", correlationId)
        .retrieve().bodyToMono(MediaIngestionWire.class).map(MediaIngestionWebClientAdapter::view).transform(this::translate);
  }

  @Override
  public Mono<MediaIngestionView> complete(String ownerSubject, String id, Long sizeBytes, String correlationId) {
    return this.complete(ownerSubject, id, sizeBytes, correlationId, List.of());
  }

  @Override
  public Mono<MediaIngestionView> complete(String ownerSubject, String id, Long sizeBytes,
      String correlationId, List<CompletedPart> parts) {
    var payload = new java.util.HashMap<String, Object>();
    if (sizeBytes != null) payload.put("size_bytes", sizeBytes);
    if (parts != null && !parts.isEmpty()) payload.put("parts", parts);
    return this.client.post().uri(API + "/{id}/complete", UUID.fromString(id)).contentType(MediaType.APPLICATION_JSON)
        .header("X-Correlation-ID", correlationId).bodyValue(payload).retrieve()
        .bodyToMono(MediaIngestionWire.class).map(MediaIngestionWebClientAdapter::view).transform(this::translate);
  }

  @Override
  public Mono<MediaIngestionView> cancel(String ownerSubject, String id, String correlationId) {
    return this.client.post().uri(API + "/{id}/cancel", UUID.fromString(id)).header("X-Correlation-ID", correlationId)
        .retrieve().bodyToMono(MediaIngestionWire.class).map(MediaIngestionWebClientAdapter::view).transform(this::translate);
  }

  private static MediaIngestionView view(MediaIngestionWire x) {
    return new MediaIngestionView(x.ingestionId, x.actorId, x.catalogItemId, x.uploadId, x.phase,
        x.failureCode, x.uploadUrl, x.storageKey, x.fileSize, x.mimeType, x.strategy,
        x.partSizeBytes, x.totalParts);
  }

  private <T> Mono<T> translate(Mono<T> call) {
    return call.onErrorResume(org.springframework.web.reactive.function.client.WebClientResponseException.class, ex ->
        ex.getStatusCode().is5xxServerError()
            ? Mono.error(new com.guille.media.bff.experience.addmedia.application.DownstreamUnavailableException(
                ex.getStatusCode().value(), "DOWNSTREAM_UNAVAILABLE", ex.getMessage()))
             : reject(ex))
        .onErrorResume(org.springframework.web.reactive.function.client.WebClientRequestException.class, ex ->
            Mono.error(new com.guille.media.bff.experience.addmedia.application.DownstreamUnavailableException(
                503, "DOWNSTREAM_UNREACHABLE", ex.getMessage())));
  }

  private static <T> Mono<T> reject(org.springframework.web.reactive.function.client.WebClientResponseException ex) {
    log.warn("Downstream rejected media ingestion: status={} message={} body={}",
        ex.getStatusCode().value(), ex.getMessage(), ex.getResponseBodyAsString());
    return Mono.error(new com.guille.media.bff.experience.addmedia.application.DownstreamRejectionException(
        ex.getStatusCode().value(), "downstream request rejected"));
  }

  private record CreateFile(String filename, @JsonProperty("file_size") long fileSize,
      @JsonProperty("mime_type") String mimeType) {}
  static class MediaIngestionWire {
    public String ingestionId, actorId, uploadId, phase, failureCode, uploadUrl, storageKey, mimeType;
    public Long catalogItemId;
    public long fileSize;
    @com.fasterxml.jackson.annotation.JsonProperty("uploadStrategy") public String strategy;
    public Long partSizeBytes;
    public Integer totalParts;
  }
}
