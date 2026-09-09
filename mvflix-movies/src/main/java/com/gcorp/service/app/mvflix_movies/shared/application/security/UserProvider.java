package com.gcorp.service.app.mvflix_movies.shared.application.security;

import reactor.core.publisher.Mono;

public interface UserProvider {

    Mono<AuthenticatedUser> getAuthenticatedUser();

    /** Contexto para lecturas que admiten visitantes anónimos. */
    default Mono<ViewerContext> getViewerContext() {
        Mono<AuthenticatedUser> authenticated = getAuthenticatedUser();
        if (authenticated == null) {
            return Mono.just(ViewerContext.anonymous());
        }
        return authenticated.map(ViewerContext::authenticated);
    }
}
