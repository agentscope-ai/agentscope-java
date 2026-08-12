/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.config;

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.web.auth.EnvironmentKeyAuthFilter;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.auth.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux Security configuration for the builder data plane.
 *
 * <ul>
 *   <li>{@code /actuator/health|info} — public
 *   <li>{@code /api/environments/{id}/work/**} and self-hosted session routes — authenticated by
 *       {@link EnvironmentKeyAuthFilter} ({@code X-Builder-Environment-Key})
 *   <li>{@code /api/**} — requires an authenticated principal: a user JWT (same signing secret as
 *       the control plane), an environment key, or the internal token ({@code
 *       X-Builder-Internal-Token}) for plane-to-plane calls from the scheduler / control plane
 *   <li>{@code /**} — public (the data plane serves no browser assets; only {@code /api/**} is
 *       protected)
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class DataSecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            JwtService jwtService,
            ControlPlaneClient controlPlaneClient,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeExchange(
                        auth ->
                                auth.pathMatchers("/actuator/health", "/actuator/info")
                                        .permitAll()
                                        .pathMatchers("/api/**")
                                        .authenticated()
                                        .anyExchange()
                                        .permitAll())
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        new InternalTokenAuthFilter(internalToken),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterBefore(
                        new EnvironmentKeyAuthFilter(controlPlaneClient::verifyEnvironmentKey),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterBefore(
                        new JwtAuthFilter(jwtService), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** WebFilter that extracts and validates the JWT Bearer token from each request. */
    static class JwtAuthFilter implements WebFilter {

        private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

        private final JwtService jwtService;

        JwtAuthFilter(JwtService jwtService) {
            this.jwtService = jwtService;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            String header = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                return chain.filter(exchange);
            }
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                String userId = jwtService.extractUserId(claims);
                List<String> roles = jwtService.extractRoles(claims);
                List<SimpleGrantedAuthority> authorities =
                        roles.stream()
                                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
                                .toList();
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                return chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
            } catch (JwtException e) {
                log.debug("JWT validation failed: {}", e.getMessage());
                return chain.filter(exchange);
            }
        }
    }
}
