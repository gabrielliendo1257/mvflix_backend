package com.gcorp.service.app.mvflix_playback.infrastructure.web;

import com.gcorp.service.app.mvflix_playback.application.StartPlayback;
import com.gcorp.service.app.mvflix_playback.application.RecordPlaybackProgress;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ServerWebInputException;
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
  public ResponseEntity<ProblemDetail> optimisticLockConflict() {
    return problem(HttpStatus.CONFLICT, "PLAYBACK_CONFLICT", "Playback progress was modified concurrently");
  }

  @org.springframework.web.bind.annotation.ExceptionHandler(RecordPlaybackProgress.PlaybackSessionForbiddenException.class)
  public ResponseEntity<ProblemDetail> forbidden(RecordPlaybackProgress.PlaybackSessionForbiddenException error) {
    return problem(HttpStatus.FORBIDDEN, "PLAYBACK_FORBIDDEN", error.getMessage());
  }

  @org.springframework.web.bind.annotation.ExceptionHandler(RecordPlaybackProgress.PlaybackSessionNotFoundException.class)
  public ResponseEntity<ProblemDetail> notFound(RecordPlaybackProgress.PlaybackSessionNotFoundException error) {
    return problem(HttpStatus.NOT_FOUND, "PLAYBACK_SESSION_NOT_FOUND", error.getMessage());
  }

  @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException error) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_PLAYBACK_REQUEST", error.getMessage());
  }

  @org.springframework.web.bind.annotation.ExceptionHandler(ServerWebInputException.class)
  public ResponseEntity<ProblemDetail> invalidInput(ServerWebInputException error) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_PLAYBACK_REQUEST", "Request body is invalid");
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code);
    return ResponseEntity.status(status).body(problem);
  }

  public record ProgressRequest(long sequence, long positionSeconds, Long durationSeconds,
      boolean completed) {}

  public record ProgressResponse(long sequence, Long positionSeconds, String status) {}
}
