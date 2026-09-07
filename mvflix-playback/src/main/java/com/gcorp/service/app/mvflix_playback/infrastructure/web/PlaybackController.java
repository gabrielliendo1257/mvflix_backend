package com.gcorp.service.app.mvflix_playback.infrastructure.web;

import com.gcorp.service.app.mvflix_playback.application.StartPlayback;
import com.gcorp.service.app.mvflix_playback.application.RecordPlaybackProgress;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/playback")
public class PlaybackController {
  private final StartPlayback startPlayback;
  private final RecordPlaybackProgress recordProgress;

  public PlaybackController(StartPlayback startPlayback, RecordPlaybackProgress recordProgress) {
    this.startPlayback = startPlayback;
    this.recordProgress = recordProgress;
  }

  @PostMapping("/sessions/{catalogItemId}")
  public Mono<ResponseEntity<PlaybackResponse>> start(@PathVariable long catalogItemId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
    return startPlayback.execute(new CatalogItemId(catalogItemId), new ViewerId(jwt.getSubject()),
        authorization)
        .map(PlaybackResponse::from)
        .map(ResponseEntity::ok);
  }

  @PostMapping("/sessions/{sessionId}/progress")
  public Mono<ResponseEntity<ProgressResponse>> progress(@PathVariable java.util.UUID sessionId,
      @AuthenticationPrincipal Jwt jwt, @RequestBody ProgressRequest request) {
    return recordProgress.execute(new PlaybackSessionId(sessionId), new ViewerId(jwt.getSubject()),
        request.sequence(), request.positionSeconds(), request.durationSeconds(), request.completed())
        .map(session -> ResponseEntity.ok(new ProgressResponse(session.lastSequence(),
            session.lastPosition() == null ? null : session.lastPosition().seconds(),
            session.status().name())));
  }

  @org.springframework.web.bind.annotation.ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<Void> optimisticLockConflict() {
    return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).build();
  }

  public record ProgressRequest(long sequence, long positionSeconds, Long durationSeconds,
      boolean completed) {}

  public record ProgressResponse(long sequence, Long positionSeconds, String status) {}
}
