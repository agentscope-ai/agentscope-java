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
package io.agentscope.builder.portal.config;

import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.auth.InternalTokenStartupValidator;
import io.agentscope.builder.web.auth.JwtService;
import io.agentscope.builder.web.auth.LocalDevAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;

/** Runtime routes perform their caller and scope checks in the Session Portal admission services. */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@Import({InternalTokenStartupValidator.class, JwtService.class})
public class PortalSecurityConfig {
    @Bean
    public SecurityWebFilterChain sessionPortalSecurity(
            ServerHttpSecurity http,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken,
            @Value("${builder.local-dev:${BUILDER_LOCAL_DEV:false}}") boolean localDev) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));
        if (localDev)
            return http.authorizeExchange(access -> access.anyExchange().permitAll())
                    .addFilterBefore(
                            new LocalDevAuthFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                    .build();
        return http.authorizeExchange(
                        access ->
                                access.pathMatchers("/actuator/health", "/actuator/info")
                                        .permitAll()
                                        .pathMatchers("/api/internal/**")
                                        .hasAuthority(InternalTokenAuthFilter.ROLE_INTERNAL)
                                        .pathMatchers(
                                                "/api/v1/runtime-hosts/**",
                                                "/api/v1/agent-tasks/*/runtime-approvals",
                                                "/api/v1/agent-tasks/*/runtime-approvals/*/decision",
                                                "/api/v1/agent-tasks/*/runtime-approvals/*/ack",
                                                "/api/v1/agent-tasks/**",
                                                "/api/v1/issues/*",
                                                "/api/v1/issues/*/comments",
                                                "/api/v1/teams/*",
                                                "/mcp/collaboration",
                                                "/api/v1/agent-sessions",
                                                "/api/v1/agent-sessions/**",
                                                "/v1/sessions",
                                                "/v1/sessions/**")
                                        .permitAll()
                                        .anyExchange()
                                        .denyAll())
                .addFilterBefore(
                        new InternalTokenAuthFilter(internalToken),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
