package com.gcorp.service.app.mvflix_movies.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import com.gcorp.mvflix.security.webflux.MvflixSecurityAutoConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = ActuatorSecurityTest.Endpoint.class)
@Import({SecurityConfig.class, MvflixSecurityAutoConfiguration.class, ActuatorSecurityTest.Endpoint.class})
@ActiveProfiles("security-test")
@TestPropertySource(properties = {
        "ACTUATOR_METRICS_USER=metrics",
        "ACTUATOR_METRICS_PASSWORD=change-me",
        "security.oauth2.jwk-set-uri=http://authorization.invalid/oauth2/jwks"
})
class ActuatorSecurityTest {

    @Autowired private WebTestClient webTestClient;

    @Test
    void actuatorRequiresValidBasicAuth() {
        this.webTestClient.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isUnauthorized();
        this.webTestClient.get().uri("/actuator/prometheus")
                .headers(headers -> headers.setBasicAuth("metrics", "wrong"))
                .exchange().expectStatus().isUnauthorized();
        this.webTestClient.get().uri("/actuator/prometheus")
                .headers(headers -> headers.setBasicAuth("metrics", "change-me"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void readinessIsPublicForContainerProbes() {
        this.webTestClient.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk();
    }

    @RestController
    public static class Endpoint {
        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return "# HELP test_metric 1\n";
        }

        @GetMapping("/actuator/health/readiness")
        String readiness() {
            return "{\"status\":\"UP\"}";
        }
    }
}
