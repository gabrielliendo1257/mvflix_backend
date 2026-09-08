package com.gcorp.mvflix.security.webflux;

import java.security.Principal;
import java.util.stream.Collectors;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public final class AuthenticatedActorArgumentResolver implements HandlerMethodArgumentResolver {
  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(CurrentActor.class)
        && AuthenticatedActor.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public Mono<Object> resolveArgument(
      MethodParameter parameter, BindingContext bindingContext, ServerWebExchange exchange) {
    return exchange
        .getPrincipal()
        .switchIfEmpty(Mono.error(new AuthenticationCredentialsNotFoundException(
            "authenticated actor is required")))
        .map(this::toActor)
        .cast(Object.class);
  }

  private AuthenticatedActor toActor(Principal principal) {
    if (!(principal instanceof Authentication authentication)
        || authentication instanceof AnonymousAuthenticationToken
        || !authentication.isAuthenticated()) {
      throw new AuthenticationCredentialsNotFoundException("authenticated actor is required");
    }
    String subject = authentication.getPrincipal() instanceof Jwt jwt
        ? jwt.getSubject()
        : authentication.getName();
    if (subject == null || subject.isBlank()) {
      throw new AuthenticationCredentialsNotFoundException("authenticated actor subject is required");
    }
    return new AuthenticatedActor(subject, authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .collect(Collectors.toUnmodifiableSet()));
  }
}
