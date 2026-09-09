package com.gcorp.service.app.authorizationservice.infrastructure.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.gcorp.service.app.authorizationservice.infrastructure.persistence.jpa.customer.CustomerEntity;
import com.gcorp.service.app.authorizationservice.infrastructure.persistence.jpa.customer.CustomerMapper;
import com.gcorp.service.app.authorizationservice.infrastructure.persistence.jpa.customer.CustomerRepository;
import com.gcorp.service.app.authorizationservice.models.Authorization;
import com.gcorp.service.app.authorizationservice.models.CustomerSecurity;
import com.gcorp.service.app.authorizationservice.models.Role;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Dev-token habilitado solo en dev (ver application-dev.yml). */
    @Value("${authorization.dev-token.enabled:false}")
    private boolean devTokenEnabled;

    // @Value("${authorization.env.jwk.uri}")
    // private String jwkUrl;

    @Bean
    RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(value = 2)
    SecurityFilterChain configuration(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            // .oauth2ResourceServer(resourceServer ->
            //     resourceServer.jwt(jwtConfig ->
            //         jwtConfig.jwkSetUri(this.jwkUrl)
            //     )
            // )
            .authorizeHttpRequests(authorize -> {
                authorize
                    .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**")
                    .permitAll();
                if (this.devTokenEnabled) {
                    // Solo dev (authorization.dev-token.enabled=true): el controller ni
                    // siquiera existe fuera de dev, asi que no hay emision en prod.
                    authorize.requestMatchers("/oauth2/dev-token").permitAll();
                }
                authorize.anyRequest().authenticated();
            })
            .formLogin(form -> form.loginPage("/login"));

        return http.build();
    }

    @Bean
    AuthenticationManager authenticationProvider(
        PasswordEncoder passwordEncoder,
        @Qualifier("userDetailService") UserDetailsService userDetailsService
    ) {
        DaoAuthenticationProvider daoAuthenticationProvider = new DaoAuthenticationProvider();
        daoAuthenticationProvider.setUserDetailsService(userDetailsService);
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder);

        return new ProviderManager(daoAuthenticationProvider);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(
        @Value(value = "${spring.application.name}") String applicationName
    ) {
        return context -> {
            var authentication = context.getPrincipal();

            if (
                authentication instanceof
                    UsernamePasswordAuthenticationToken authenticationToken
            ) {
                if (context.getTokenType() == OAuth2TokenType.ACCESS_TOKEN
                    || OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
                    List<String> roles = new ArrayList<>(
                        authenticationToken
                            .getAuthorities()
                            .stream()
                            .map(GrantedAuthority::getAuthority)
                            .toList());

                    context.getClaims().claim("roles", roles);
                    if (context.getTokenType() == OAuth2TokenType.ACCESS_TOKEN) {
                        context.getClaims().audience(new ArrayList<>(List.of(applicationName)));
                        context.getClaims().subject(authenticationToken.getName());
                    }

                    log.info("Roles: {}", roles);
                }
            }
        };
    }

    @Bean
    JwtAuthenticationConverter authenticationConverter() {
        var jwtConverter = new JwtGrantedAuthoritiesConverter();
        jwtConverter.setAuthorityPrefix("");
        jwtConverter.setAuthoritiesClaimName("roles");

        var jwtAuthConverter = new JwtAuthenticationConverter();
        jwtAuthConverter.setJwtGrantedAuthoritiesConverter(jwtConverter);

        return jwtAuthConverter;
    }

    @Bean
    UserDetailsService userDetailService(
        CustomerRepository accountrepository,
        CustomerMapper accountMapper
    ) {
        return username -> {
            CustomerEntity accountEntity = accountrepository
                .findSecurityCustomerByUsername(username)
                .orElseThrow(() ->
                    new UsernameNotFoundException("Ususario no encontrado.")
                );
            Set<Authorization> authorizations = accountEntity
                .getRole()
                .getAuthorities()
                .stream()
                .map(rol -> new Authorization(rol.getName()))
                .collect(Collectors.toSet());

            CustomerSecurity customerSecurity = CustomerSecurity.builder()
                .username(accountEntity.getUsername())
                .password(accountEntity.getPassword())
                .role(
                    new Role(
                        accountEntity.getRole().getRoleName(),
                        authorizations
                    )
                )
                .build();
            log.info("SecurityAccount after mapper: {}", customerSecurity);

            return customerSecurity;
        };
    }
}
