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
package io.agentscope.harness.claw.web.config;

import io.agentscope.harness.claw.web.auth.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Security configuration for the agentscope-claw-web admin console.
 *
 * <p>All {@code /api/**} endpoints require {@code ROLE_ADMIN}. Admin users authenticate against
 * agentscope-claw's {@code POST /api/auth/login} and obtain a JWT; that same token is accepted
 * here because both services share the same signing secret.
 *
 * <ul>
 *   <li>{@code /actuator/health}, {@code /actuator/info} — public
 *   <li>{@code /api/**} — requires authenticated user with {@code ROLE_ADMIN}
 *   <li>{@code /**} — public (React SPA static files)
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, JwtService jwtService) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeExchange(
                        auth ->
                                auth.pathMatchers("/actuator/health", "/actuator/info")
                                        .permitAll()
                                        .pathMatchers("/api/**")
                                        .hasRole("ADMIN")
                                        .anyExchange()
                                        .permitAll())
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        new JwtAuthFilter(jwtService), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

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
