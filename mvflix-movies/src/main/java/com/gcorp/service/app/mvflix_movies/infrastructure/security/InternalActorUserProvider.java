package com.gcorp.service.app.mvflix_movies.infrastructure.security;

import com.gcorp.service.app.mvflix_movies.shared.application.security.AuthenticatedUser;
import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Uses the explicit actor contract only for an authenticated ingestion client. */
@Component
@Primary
@Profile("!sandbox")
public class InternalActorUserProvider implements UserProvider {
  public static final Object EXCHANGE_CONTEXT_KEY = InternalActorUserProvider.class.getName();
  private static final String INTERNAL_AUTHORITY = "SCOPE_media-ingestion";
  private static final String PLAYBACK_AUTHORITY = "SCOPE_playback.sessions.write";

  private final JwtUserProvider publicUserProvider;

  public InternalActorUserProvider(JwtUserProvider publicUserProvider) {
    this.publicUserProvider = publicUserProvider;
  }

  @Override
  public Mono<AuthenticatedUser> getAuthenticatedUser() {
    return ReactiveSecurityContextHolder.getContext()
        .map(SecurityContext::getAuthentication)
        .flatMap(this::internalUser)
        .switchIfEmpty(this.publicUserProvider.getAuthenticatedUser());
  }

  private Mono<AuthenticatedUser> internalUser(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication.getAuthorities().stream()
            .noneMatch(authority -> INTERNAL_AUTHORITY.equals(authority.getAuthority())
                || PLAYBACK_AUTHORITY.equals(authority.getAuthority()))) {
      return Mono.empty();
    }
    return Mono.deferContextual(
        context -> {
          var exchange = context.getOrDefault(EXCHANGE_CONTEXT_KEY, null);
          if (!(exchange instanceof ServerWebExchange serverWebExchange)) {
            return Mono.error(
                new AuthenticationCredentialsNotFoundException(
                    "Request context required for internal authentication"));
          }
           boolean playback = authentication.getAuthorities().stream()
               .anyMatch(authority -> PLAYBACK_AUTHORITY.equals(authority.getAuthority()));
           var actor = serverWebExchange.getRequest().getHeaders().getFirst(
               playback ? "X-Viewer-Id" : "X-Actor-Id");
          return Mono.justOrEmpty(actor)
              .filter(value -> !value.isBlank())
              .map(
                  value -> new AuthenticatedUser(value, null, java.util.Set.of(INTERNAL_AUTHORITY)))
              .switchIfEmpty(
                  Mono.error(
                      new AuthenticationCredentialsNotFoundException(
                          "X-Actor-Id required for internal authentication")));
        });
  }
}
