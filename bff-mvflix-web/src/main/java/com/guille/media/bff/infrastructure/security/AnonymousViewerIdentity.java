package com.guille.media.bff.infrastructure.security;

import com.guille.media.bff.app.service.WebSessionService;
import com.guille.media.bff.experience.playback.application.port.PlaybackService;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Identidad estable del usuario autenticado o del visitante del navegador. */
@Component
public class AnonymousViewerIdentity {
  public static final String COOKIE_NAME = "MVFLIX-VIEWER-ID";
  private static final Duration COOKIE_TTL = Duration.ofDays(180);

  private final WebSessionService session;
  private final PlaybackService playback;

  public AnonymousViewerIdentity(WebSessionService session, PlaybackService playback) {
    this.session = session;
    this.playback = playback;
  }

  public Mono<String> resolve(ServerWebExchange exchange) {
    return this.session.currentSubject()
        .flatMap(subject -> mergeCookie(exchange, subject).thenReturn(subject))
        .switchIfEmpty(Mono.defer(() -> this.anonymous(exchange)));
  }

  private Mono<Void> mergeCookie(ServerWebExchange exchange, String subject) {
    var cookie = exchange.getRequest().getCookies().getFirst(COOKIE_NAME);
    if (cookie == null || !isUuid(cookie.getValue())) {
      return Mono.empty();
    }
    return playback.mergeAnonymousProgress("anonymous:" + cookie.getValue(), subject)
        // La migracion es reintentable; no debe bloquear una sesion autenticada.
        .onErrorResume(error -> Mono.empty());
  }

  private Mono<String> anonymous(ServerWebExchange exchange) {
    var cookie = exchange.getRequest().getCookies().getFirst(COOKIE_NAME);
    String existing = cookie == null ? null : cookie.getValue();
    if (existing != null && isUuid(existing)) {
      return Mono.just("anonymous:" + existing);
    }

    String value = UUID.randomUUID().toString();
    exchange.getResponse().addCookie(ResponseCookie.from(COOKIE_NAME, value)
        .httpOnly(true)
        .secure(false)
        .path("/")
        .sameSite("Lax")
        .maxAge(COOKIE_TTL)
        .build());
    return Mono.just("anonymous:" + value);
  }

  private static boolean isUuid(String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
