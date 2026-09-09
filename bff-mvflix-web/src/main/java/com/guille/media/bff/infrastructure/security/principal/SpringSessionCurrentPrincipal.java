package com.guille.media.bff.infrastructure.security.principal;

import com.guille.media.bff.experience.shell.application.port.CurrentPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.stream.Collectors;

/** Adapter sobre el mecanismo real: Spring Security reactivo de la sesión. */
@Component
public class SpringSessionCurrentPrincipal implements CurrentPrincipal {

    @Override
    public Mono<PrincipalIdentity> current() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(Authentication::isAuthenticated)
            .map(auth -> {
                var authorities = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
                if (auth.getPrincipal() instanceof OidcUser oidcUser) {
                    Object roles = oidcUser.getClaims().get("roles");
                    if (roles instanceof Iterable<?> values) {
                        for (Object role : values) {
                            if (role != null) authorities.add(String.valueOf(role));
                        }
                    }
                }
                return new PrincipalIdentity(auth.getName(), Set.copyOf(authorities));
            });
    }
}
