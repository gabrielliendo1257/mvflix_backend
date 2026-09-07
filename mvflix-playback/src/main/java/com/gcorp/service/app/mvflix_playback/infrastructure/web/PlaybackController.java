package com.gcorp.service.app.mvflix_playback.infrastructure.web;

import com.gcorp.service.app.mvflix_playback.application.StartPlayback;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/playback")
public class PlaybackController {
  private final StartPlayback startPlayback;

  public PlaybackController(StartPlayback startPlayback) {
    this.startPlayback = startPlayback;
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
}
