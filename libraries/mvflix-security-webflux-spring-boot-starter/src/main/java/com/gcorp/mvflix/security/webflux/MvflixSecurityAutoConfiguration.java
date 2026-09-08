package com.gcorp.mvflix.security.webflux;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

@AutoConfiguration
@EnableConfigurationProperties(MvflixSecurityProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass({ServerHttpSecurity.class, SecurityWebFilterChain.class})
public class MvflixSecurityAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  MvflixJwtAuthenticationConverter mvflixJwtAuthenticationConverter() {
    return new MvflixJwtAuthenticationConverter();
  }

  @Bean
  @ConditionalOnMissingBean(name = "mvflixActuatorSecurityWebFilterChain")
  @Order(0)
  SecurityWebFilterChain mvflixActuatorSecurityWebFilterChain(ServerHttpSecurity http,
      MvflixSecurityProperties properties, Environment environment) {
    if (environment.acceptsProfiles(Profiles.of("prod", "production"))
        && "change-me".equals(properties.actuatorPassword())) {
      throw new IllegalStateException(
          "mvflix.security.actuator-password must be configured in production");
    }
    return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/actuator/**"))
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authenticationManager(metricsAuthenticationManager(properties.actuatorUsername(),
            properties.actuatorPassword()))
        .httpBasic(org.springframework.security.config.Customizer.withDefaults())
        .authorizeExchange(exchanges -> exchanges
            .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
            .anyExchange().hasRole("METRICS"))
        .build();
  }

  @Bean
  @ConditionalOnMissingBean
  MvflixUnauthorizedHandler mvflixUnauthorizedHandler() {
    return new MvflixUnauthorizedHandler();
  }

  @Bean
  @ConditionalOnMissingBean
  MvflixAccessDeniedHandler mvflixAccessDeniedHandler() {
    return new MvflixAccessDeniedHandler();
  }

  private ReactiveAuthenticationManager metricsAuthenticationManager(String username, String password) {
    var user = User.withUsername(username).password("{noop}" + password).roles("METRICS").build();
    return new UserDetailsRepositoryReactiveAuthenticationManager(new MapReactiveUserDetailsService(user));
  }
}
