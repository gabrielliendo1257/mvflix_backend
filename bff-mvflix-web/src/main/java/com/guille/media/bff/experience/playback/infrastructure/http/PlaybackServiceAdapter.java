package com.guille.media.bff.experience.playback.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.guille.media.bff.experience.playback.application.DirectSource;
import com.guille.media.bff.experience.playback.application.PlaybackSourceUnavailableException;
import com.guille.media.bff.experience.playback.application.port.PlaybackService;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

@Component
public class PlaybackServiceAdapter implements PlaybackService {
  private final WebClient playbackServiceWebClient;

  public PlaybackServiceAdapter(@Qualifier("playbackServiceWebClient") WebClient playbackServiceWebClient) {
    this.playbackServiceWebClient = playbackServiceWebClient;
  }

  @Override
  public Mono<StartedSession> start(long mediaId) {
    return start(mediaId, null);
  }

  @Override
  public Mono<StartedSession> start(long mediaId, String viewerId) {
    return playbackServiceWebClient.post()
        .uri("/api/v1/playback/sessions/{mediaId}", mediaId)
        .headers(headers -> { if (viewerId != null) headers.set("X-Viewer-Id", viewerId); })
        .retrieve()
        .bodyToMono(PlaybackResponse.class)
        .map(response -> new StartedSession(
            response.sessionId(),
            new DirectSource(response.source().url(), response.source().expiresAt(),
                response.source().mimeType()),
            response.resumePositionSeconds()))
        .onErrorMap(WebClientResponseException.class,
            error -> new PlaybackSourceUnavailableException(
                "Playback no pudo iniciar la sesion (" + error.getStatusCode().value() + ")", error))
        .onErrorMap(WebClientRequestException.class,
            error -> new PlaybackSourceUnavailableException("Playback no alcanzable", error));
  }

  @Override
  public Mono<ProgressResult> progress(String sessionId, ProgressCommand command) {
    return progress(sessionId, null, command);
  }

  @Override
  public Mono<ProgressResult> progress(String sessionId, String viewerId, ProgressCommand command) {
    return playbackServiceWebClient.post()
        .uri("/api/v1/playback/sessions/{sessionId}/progress", sessionId)
        .headers(headers -> { if (viewerId != null) headers.set("X-Viewer-Id", viewerId); })
        .bodyValue(command)
        .retrieve()
        .bodyToMono(ProgressResponse.class)
        .map(response -> new ProgressResult(response.sequence(), response.positionSeconds(),
            response.status()))
        .onErrorMap(WebClientResponseException.class,
            error -> new PlaybackSourceUnavailableException("Playback no pudo guardar el progreso", error))
        .onErrorMap(WebClientRequestException.class,
            error -> new PlaybackSourceUnavailableException("Playback no alcanzable", error));
  }

  @Override
  public Mono<Void> mergeAnonymousProgress(String anonymousViewerId, String authenticatedViewerId) {
    return playbackServiceWebClient.post()
        .uri("/api/v1/playback/progress/merge")
        .header("X-Viewer-Id", authenticatedViewerId)
        .bodyValue(new MergeProgressRequest(anonymousViewerId))
        .retrieve().bodyToMono(Void.class)
        .onErrorMap(WebClientResponseException.class,
            error -> new PlaybackSourceUnavailableException("Playback no pudo fusionar el progreso", error))
        .then();
  }

  record PlaybackResponse(String sessionId, Long resumePositionSeconds, Source source) {}

  record ProgressResponse(long sequence, Long positionSeconds, String status) {}

  record MergeProgressRequest(String anonymousViewerId) {}

  record Source(
      @JsonProperty("url") String url,
      @JsonProperty("expiresAt") Instant expiresAt,
      @JsonProperty("mimeType") String mimeType) {}
}
