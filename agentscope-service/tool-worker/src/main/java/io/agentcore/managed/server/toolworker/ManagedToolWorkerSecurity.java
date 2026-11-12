/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.toolworker;

import io.agentcore.managed.server.web.auth.InternalTokenAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/** Worker endpoints authenticate the Environment key in their application service. */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class ManagedToolWorkerSecurity {
    @Bean
    SecurityWebFilterChain toolWorkerSecurity(
            ServerHttpSecurity http,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String internalToken) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(
                        exchanges ->
                                exchanges
                                        .pathMatchers(
                                                "/actuator/health",
                                                "/actuator/health/readiness",
                                                "/actuator/health/liveness",
                                                "/actuator/info")
                                        .permitAll()
                                        .pathMatchers("/actuator/metrics", "/actuator/metrics/**")
                                        .hasAuthority(InternalTokenAuthFilter.ROLE_INTERNAL)
                                        .pathMatchers(
                                                "/v1/tool-workers/environments/*/tool-tasks/**")
                                        .permitAll()
                                        .anyExchange()
                                        .denyAll())
                .addFilterBefore(
                        new InternalTokenAuthFilter(internalToken),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
